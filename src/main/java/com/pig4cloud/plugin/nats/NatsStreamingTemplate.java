package com.pig4cloud.plugin.nats;

import com.pig4cloud.plugin.nats.annotation.NatsStreamingSubscribe;
import com.pig4cloud.plugin.nats.enums.ConnectionType;
import com.pig4cloud.plugin.nats.exception.NatsStreamingException;
import com.pig4cloud.plugin.nats.properties.NatsStreamingProperties;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.streaming.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Description:
 *
 * @author 谢宇 Date: 2019/8/30 030 下午 9:11
 */
@Slf4j
public class NatsStreamingTemplate {

	@Resource
	private Environment env;

	// 连接实例
	private StreamingConnection sc;

	private SubscriptionOptions options;

	private HashMap<String, Object> subBeans = new HashMap<>();

	private Queue<PublishCache> msgQueue;

	private Lock publishCacheLock = new ReentrantLock();

	NatsStreamingTemplate(SubscriptionOptions options) {
		this.options = options;
	}

	/**
	 * 连接nats streaming
	 * @param properties yaml配置
	 */
	void connect(NatsStreamingProperties properties) {
		Options opts = new Options.Builder().natsUrl(properties.getUrls()).maxPingsOut(properties.getMaxPingsOut())
				.pingInterval(Duration.ofSeconds(properties.getPingInterval())).connectionLostHandler((conn, ex) -> {
					try {
						this.close();
					}
					catch (InterruptedException e) {
						log.error("Nats Streaming(" + properties.getUrls() + ")连接关闭失败", e);
					}
					this.connect(properties);
					subBeans.forEach((beanName, bean) -> doSub(bean, beanName, true));
					republish();
				}).build();
		while (true) {
			log.info("开始连接 Nats Streaming({})...", properties.getUrls());
			try {
				this.sc = NatsStreaming.connect(properties.getClusterId(), properties.getClientId(), opts);
				break;
			}
			catch (IOException | InterruptedException e) {
				log.error("Nats Streaming(" + properties.getUrls() + ")连接失败，" + properties.getReConnInterval()
						+ "秒后重新尝试连接", e);
			}

			try {
				Thread.sleep(TimeUnit.SECONDS.toMillis(properties.getReConnInterval()));
			}
			catch (InterruptedException e) {
				log.error("重连延时失败！", e);
			}
		}
		log.info("Nats Streaming({}) 连接成功！", properties.getUrls());
	}

	void doSub(Object bean, String beanName) {
		doSub(bean, beanName, false);
	}

	/**
	 * 为提供注解的bean进行消息订阅
	 */
	private void doSub(Object bean, String beanName, boolean dataFormSubBeans) {
		final Class<?> clazz = bean.getClass();
		Arrays.stream(clazz.getMethods()).forEach(method -> {
			Optional<NatsStreamingSubscribe> sub = Optional
					.ofNullable(AnnotationUtils.findAnnotation(method, NatsStreamingSubscribe.class));
			sub.ifPresent(subscribe -> {
				String topic = subscribe.subscribe();
				// 如果用户配置了外部配置的主题，则覆盖代码内主题
				String propertyPath = subscribe.propertyPath();
				if (StringUtils.hasLength(propertyPath)) {
					String topicTemp = env.getProperty(propertyPath);
					topic = topicTemp != null ? topicTemp : topic;
				}

				String queue = subscribe.queue();

				final Class<?>[] parameterTypes = method.getParameterTypes();
				if (subscribe.connectionType() == ConnectionType.Nats) {
					if (parameterTypes.length != 1 || !parameterTypes[0].equals(io.nats.client.Message.class)) {
						throw new NatsStreamingException(String.format(
								"Method '%s' on bean with name '%s' must have a single parameter of type %s when using the @%s annotation.",
								method.toGenericString(), beanName, io.nats.client.Message.class.getName(),
								NatsStreamingSubscribe.class.getName()));
					}
					Dispatcher d = getNatsConnection().createDispatcher(msg -> {
						try {
							method.invoke(bean, msg);
						}
						catch (IllegalAccessException | InvocationTargetException e) {
							log.error("订阅回调失败", e);
						}
					});

					// 这愚蠢的写法完全是因为写驱动的人非要在subscribe(topic, queue)中要求queue不能为空
					if ("".equals(queue)) {
						d.subscribe(topic);
					}
					else {
						d.subscribe(topic, queue);
					}
					log.info("成功订阅Nats消息，主题：{}", topic);
				}
				else {
					if (parameterTypes.length != 1 || !parameterTypes[0].equals(Message.class)) {
						throw new NatsStreamingException(String.format(
								"Method '%s' on bean with name '%s' must have a single parameter of type %s when using the @%s annotation.",
								method.toGenericString(), beanName, Message.class.getName(),
								NatsStreamingSubscribe.class.getName()));
					}

					queue = "".equals(queue) ? null : queue;
					try {
						subscribe(topic, queue, msg -> {
							try {
								log.info("收到消息：{}", new String(msg.getData(), StandardCharsets.UTF_8));
								method.invoke(bean, msg);
							}
							catch (IllegalAccessException | InvocationTargetException e) {
								log.error("订阅回调失败", e);
							}
						}, options);
						log.info("成功订阅Nats Streaming消息，主题：{}", topic);
					}
					catch (IOException | InterruptedException | TimeoutException e) {
						log.error("Nats Streaming异常", e);
						Thread.currentThread().interrupt();
					}
				}
				if (!dataFormSubBeans) {
					this.subBeans.put(beanName, bean);
				}
			});
		});
	}

	public void publish(String subject, byte[] data) throws IOException, InterruptedException, TimeoutException {
		sc.publish(subject, data);
	}

	public String publish(String subject, byte[] data, AckHandler ah)
			throws IOException, InterruptedException, TimeoutException {
		return sc.publish(subject, data, ah);
	}

	public void safePublish(String subject, byte[] data) throws TimeoutException, InterruptedException, IOException {
		this.safePublish(subject, data, null);
	}

	public String safePublish(String subject, byte[] data, AckHandler ah)
			throws TimeoutException, InterruptedException, IOException {
		try {
			return sc.publish(subject, data, (nuid, ex) -> {
				if (ex instanceof InterruptedException) {
					log.warn("网络原因消息发送失败，消息已缓存，将在恢复连接后重新发送！", ex);
					doCache(new PublishCache(subject, data, ah));
				}
				else {
					ah.onAck(nuid, ex);
				}
			});
		}
		catch (IllegalStateException e) {
			log.warn("网络原因消息发送失败，消息已缓存，将在恢复连接后重新发送！", e);
			doCache(new PublishCache(subject, data, ah));
		}
		return null;
	}

	/**
	 * 缓存网络问题发送失败的消息
	 */
	private void doCache(PublishCache cache) {
		initCache();
		publishCacheLock.lock();
		try {
			boolean success = msgQueue.offer(cache);
			while (!success) {
				PublishCache firstCache = msgQueue.poll();
				if (firstCache == null) {
					log.warn("消息缓存队列已满，放弃较早的消息");
				}
				else {
					log.warn("消息缓存队列已满，放弃较早的消息：{}", firstCache.toString());
				}
				success = msgQueue.offer(cache);
			}
		}
		finally {
			publishCacheLock.unlock();
		}
	}

	/**
	 * 初始化缓存
	 */
	private void initCache() {
		while (msgQueue == null) {
			publishCacheLock.lock();
			try {
				while (msgQueue == null) {
					msgQueue = new ConcurrentLinkedQueue<>();
				}
			}
			finally {
				publishCacheLock.unlock();
			}
		}
	}

	private void republish() {
		while (!msgQueue.isEmpty()) {
			PublishCache cache = msgQueue.poll();
			try {
				this.safePublish(cache.getSubject(), cache.getData(), cache.getAh());
			}
			catch (TimeoutException | InterruptedException | IOException e) {
				log.error("消息发送失败！", e);
			}
		}
	}

	public Subscription subscribe(String subject, MessageHandler cb)
			throws IOException, InterruptedException, TimeoutException {
		return sc.subscribe(subject, cb);
	}

	public Subscription subscribe(String subject, MessageHandler cb, SubscriptionOptions opts)
			throws IOException, InterruptedException, TimeoutException {
		return sc.subscribe(subject, cb, opts);
	}

	public Subscription subscribe(String subject, String queue, MessageHandler cb)
			throws IOException, InterruptedException, TimeoutException {
		return sc.subscribe(subject, queue, cb);
	}

	public Subscription subscribe(String subject, String queue, MessageHandler cb, SubscriptionOptions opts)
			throws IOException, InterruptedException, TimeoutException {
		return sc.subscribe(subject, queue, cb, opts);
	}

	public Connection getNatsConnection() {
		return sc.getNatsConnection();
	}

	public void close() throws InterruptedException {
		if (this.sc.getNatsConnection() != null) {
			this.sc.getNatsConnection().close();
		}
	}

}
