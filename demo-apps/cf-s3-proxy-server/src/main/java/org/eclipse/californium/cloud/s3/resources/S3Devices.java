/********************************************************************************
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
 * 
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 * 
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
 ********************************************************************************/
package org.eclipse.californium.cloud.s3.resources;

import static org.eclipse.californium.core.coap.CoAP.ResponseCode.BAD_OPTION;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CHANGED;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.FORBIDDEN;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.INTERNAL_SERVER_ERROR;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.NOT_ACCEPTABLE;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_CBOR;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_JAVASCRIPT;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_JSON;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_LINK_FORMAT;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_OCTET_STREAM;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.APPLICATION_XML;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.TEXT_PLAIN;
import static org.eclipse.californium.core.coap.MediaTypeRegistry.UNDEFINED;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;

import org.eclipse.californium.cloud.BaseServer;
import org.eclipse.californium.cloud.option.ReadEtagOption;
import org.eclipse.californium.cloud.option.ReadResponseOption;
import org.eclipse.californium.cloud.option.TimeOption;
import org.eclipse.californium.cloud.resources.ProtectedCoapResource;
import org.eclipse.californium.cloud.s3.option.ForwardResponseOption;
import org.eclipse.californium.cloud.s3.proxy.S3AsyncProxyClient;
import org.eclipse.californium.cloud.s3.proxy.S3ProxyClient;
import org.eclipse.californium.cloud.s3.proxy.S3ProxyClientProvider;
import org.eclipse.californium.cloud.s3.proxy.S3ProxyRequest;
import org.eclipse.californium.cloud.s3.proxy.S3ProxyRequest.Builder;
import org.eclipse.californium.cloud.s3.util.DomainPrincipalInfo;
import org.eclipse.californium.cloud.s3.util.HttpForwardDestinationProvider;
import org.eclipse.californium.cloud.s3.util.HttpForwardDestinationProvider.DeviceIdentityMode;
import org.eclipse.californium.cloud.util.PrincipalInfo;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.WebLink;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.coap.Option;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.coap.ResponseConsumer;
import org.eclipse.californium.core.coap.UriQueryParameter;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.core.server.resources.ResourceAttributes;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.util.Bytes;
import org.eclipse.californium.elements.util.LeastRecentlyUpdatedCache;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.proxy2.http.Coap2HttpProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Devices resource.
 * <p>
 * Keeps the content of POST request as sub-resource using the principal's name
 * and domain as path of the sub-resource. e.g.:
 * </p>
 * 
 * <code>
 * coaps://${host}/devices POST "Hi!" by principal "Client_identity"
 * </code>
 * 
 * <p>
 * results in a resource:
 * </p>
 * 
 * <code>
 * "/devices/${device-domain}/Client_identity" with content "Hi!".
 * </code>
 * 
 * A GET request must then use that path in order to read the content.
 * 
 * <code>
 * coaps://${host}/devices/${device-domain}/Client_identity GET result in "Hi!"
 * </code>
 * 
 * <p>
 * Supported content types:
 * </p>
 * 
 * <ul>
 * <li>{@link MediaTypeRegistry#TEXT_PLAIN}</li>
 * <li>{@link MediaTypeRegistry#APPLICATION_OCTET_STREAM}</li>
 * <li>{@link MediaTypeRegistry#APPLICATION_CBOR}</li>
 * <li>{@link MediaTypeRegistry#APPLICATION_JSON}</li>
 * <li>{@link MediaTypeRegistry#APPLICATION_XML}</li>
 * <li>{@link MediaTypeRegistry#APPLICATION_JAVASCRIPT}</li>
 * </ul>
 * 
 * <p>
 * For GET, {@link MediaTypeRegistry#APPLICATION_LINK_FORMAT} is also supported
 * and returns a list of web-links for the current devices with public access
 * (ACL).
 * </p>
 * 
 * <p>
 * Supported query parameter:
 * </p>
 * 
 * <dl>
 * <dt>{@value #URI_QUERY_OPTION_ACL}</dt>
 * <dd>ACL for S3 objects. Default: "private".</dd>
 * <dt>{@value #URI_QUERY_OPTION_READ}</dt>
 * <dd>Sub resource for piggybacked read. Default argument "config".</dd>
 * <dt>{@value #URI_QUERY_OPTION_WRITE}</dt>
 * <dd>Sub resource to save message. Default argument "${now}".</dd>
 * <dt>{@value #URI_QUERY_OPTION_SERIES}</dt>
 * <dd>Use sub resource "series" to keep track of parts of the message.</dd>
 * </dl>
 * 
 * Default argument only applies, if the parameter is provided, but without
 * argument.
 * 
 * <p>
 * Supported custom options:
 * </p>
 * 
 * <dl>
 * <dt>{@link TimeOption}, {@value TimeOption#COAP_OPTION_TIME}</dt>
 * <dd>Time synchronization.</dd>
 * <dt>{@link ReadResponseOption},
 * {@value ReadResponseOption#COAP_OPTION_READ_RESPONSE}</dt>
 * <dd>Response code of piggybacked read request. See query parameter
 * {@value #URI_QUERY_OPTION_READ}</dd>
 * <dt>{@link ReadEtagOption},
 * {@value ReadEtagOption#COAP_OPTION_READ_ETAG}</dt>
 * <dd>ETAG of piggybacked read request. See query parameter
 * {@value #URI_QUERY_OPTION_READ}</dd>
 * </dl>
 * 
 * Example:
 * 
 * <code>
 * coaps://${host}/devices?acl=public-read&amp;read&amp;write" POST "Temperature: 25.4°"
 *  by principal "dev-1200045", domain "weather"
 * </code>
 * 
 * <p>
 * results in a S3 resource:
 * </p>
 * 
 * <code>
 * s3://${weather-bucket}/devices/dev-1200045/2022-11-03/17:14:46.645" with content "Temperature: 25.4°".
 * </code>
 * 
 * <p>
 * (Default for "write" argument is "${date}/${time}".)
 * 
 * and returns the content of
 * </p>
 * 
 * <code>
 * s3://${weather-bucket}/devices/dev-1200045/config".
 * </code>
 * 
 * <p>
 * (Default for "read" argument is "config".)
 * </p>
 * 
 * @since 3.12
 */
public class S3Devices extends ProtectedCoapResource {

	private static final Logger LOGGER = LoggerFactory.getLogger(S3Devices.class);

	public static final int SERIES_MAX_SIZE = 32 * 1024;

	public static final String RESOURCE_NAME = "devices";
	public static final String SUB_RESOURCE_NAME = "series";

	public static final String DEFAULT_READ_SUB_RESOURCE_NAME = "config";
	public static final String DEFAULT_WRITE_SUB_RESOURCE_NAME = "${date}/${time}";

	public static final String ATTRIBUTE_TIME = "time";
	public static final String ATTRIBUTE_POSITION = "pos";
	public static final String ATTRIBUTE_S3_LINK = "s3";

	/**
	 * URI query parameter to specify the ACL for S3.
	 */
	public static final String URI_QUERY_OPTION_ACL = "acl";
	/**
	 * URI query parameter to specify a sub-resource to read.
	 */
	public static final String URI_QUERY_OPTION_READ = "read";
	/**
	 * URI query parameter to specify a sub-resource to write.
	 */
	public static final String URI_QUERY_OPTION_WRITE = "write";
	/**
	 * URI query parameter to append some lines to a series-resource.
	 */
	public static final String URI_QUERY_OPTION_SERIES = "series";
	/**
	 * URI query parameter to forward request via http.
	 * 
	 * @since 3.13
	 */
	public static final String URI_QUERY_OPTION_FORWARD = "forward";
	/**
	 * Supported query parameter.
	 */
	private static final List<String> SUPPORTED = Arrays.asList(URI_QUERY_OPTION_READ, URI_QUERY_OPTION_WRITE,
			URI_QUERY_OPTION_SERIES, URI_QUERY_OPTION_ACL, URI_QUERY_OPTION_FORWARD);

	private final long minutes;

	private final int maxDevices;

	private final ConcurrentHashMap<String, Resource> domains;

	private final S3ProxyClientProvider s3Clients;

	private final Coap2HttpProxy httpForward;

	private final HttpForwardDestinationProvider httpDestination;

	private final int[] CONTENT_TYPES = { TEXT_PLAIN, APPLICATION_OCTET_STREAM, APPLICATION_JSON, APPLICATION_CBOR,
			APPLICATION_XML, APPLICATION_JAVASCRIPT, APPLICATION_LINK_FORMAT };

	/**
	 * Create devices resource.
	 * 
	 * @param config configuration
	 * @param s3Clients s3 client to persist the requests.
	 * @param httpDestination http destination to forward requests.
	 */
	public S3Devices(Configuration config, S3ProxyClientProvider s3Clients,
			HttpForwardDestinationProvider httpDestination) {
		super(RESOURCE_NAME);
		if (s3Clients == null) {
			throw new NullPointerException("s3client must not be null!");
		}
		Arrays.sort(CONTENT_TYPES);
		getAttributes().setTitle("Resource, which keeps track of POSTing devices.");
		getAttributes().addContentTypes(CONTENT_TYPES);
		minutes = config.get(BaseServer.CACHE_STALE_DEVICE_THRESHOLD, TimeUnit.MINUTES);
		maxDevices = config.get(BaseServer.CACHE_MAX_DEVICES);
		domains = new ConcurrentHashMap<>();
		this.s3Clients = s3Clients;
		Coap2HttpProxy http = null;
		if (httpDestination != null) {
			http = new Coap2HttpProxy(null);
			LOGGER.info("Forward to http enabled.");
		}
		this.httpForward = http;
		this.httpDestination = httpDestination;
	}

	@Override
	public void add(Resource child) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean delete(Resource child) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public Resource getChild(String name) {
		return domains.get(name);
	}

	@Override // should be used for read-only
	public Collection<Resource> getChildren() {
		return domains.values();
	}

	@Override
	public void handleGET(final CoapExchange exchange) {
		Request request = exchange.advanced().getRequest();
		int accept = request.getOptions().getAccept();
		if (accept != UNDEFINED && accept != APPLICATION_LINK_FORMAT) {
			exchange.respond(NOT_ACCEPTABLE);
		} else {
			final String domain = DomainPrincipalInfo.getDomain(getPrincipal(exchange));
			List<String> query = exchange.getRequestOptions().getUriQuery();
			if (query.size() > 1) {
				exchange.respond(BAD_OPTION, "only one search query is supported!", TEXT_PLAIN);
				return;
			}
			Set<WebLink> subTree = new ConcurrentSkipListSet<>();
			Resource resource = domains.get(domain);
			if (resource != null) {
				LinkFormat.addSubTree(resource, query, subTree);
			}
			Response response = new Response(CONTENT);
			response.setPayload(LinkFormat.serialize(subTree));
			response.getOptions().setContentFormat(APPLICATION_LINK_FORMAT);
			exchange.respond(response);
		}
	}

	@Override
	public void handlePOST(final CoapExchange exchange) {

		int format = exchange.getRequestOptions().getContentFormat();
		if (format != UNDEFINED && Arrays.binarySearch(CONTENT_TYPES, format) < 0) {
			exchange.respond(NOT_ACCEPTABLE);
			return;
		}

		final DomainPrincipalInfo info = DomainPrincipalInfo.getPrincipalInfo(getPrincipal(exchange));
		boolean updateSeries = false;
		boolean forward = false;
		String read = null;
		String write = null;
		try {
			UriQueryParameter helper = exchange.getRequestOptions().getUriQueryParameter(SUPPORTED);
			LOGGER.info("URI-Query: {} {}", exchange.getRequestOptions().getUriQuery(), info);
			List<Option> others = exchange.getRequestOptions().getOthers();
			if (!others.isEmpty()) {
				LOGGER.info("Other options: {} {}", others, info);
			}
			updateSeries = helper.hasParameter(URI_QUERY_OPTION_SERIES);
			forward = helper.hasParameter(URI_QUERY_OPTION_FORWARD);
			if (helper.hasParameter(URI_QUERY_OPTION_READ)) {
				read = helper.getArgument(URI_QUERY_OPTION_READ, DEFAULT_READ_SUB_RESOURCE_NAME);
				if (read.startsWith("/")) {
					throw new IllegalArgumentException("Absolute URI not supported for 'read'!");
				}
			}
			if (helper.hasParameter(URI_QUERY_OPTION_WRITE)) {
				write = helper.getArgument(URI_QUERY_OPTION_WRITE, DEFAULT_WRITE_SUB_RESOURCE_NAME);
				if (write.startsWith("/")) {
					throw new IllegalArgumentException("Absolute URI not supported for 'write'!");
				}
			}
		} catch (IllegalArgumentException ex) {
			Response response = new Response(BAD_OPTION);
			response.setPayload(ex.getMessage());
			exchange.respond(response);
			return;
		}

		Request request = exchange.advanced().getRequest();
		final TimeOption timeOption = TimeOption.getMessageTime(request);
		final long time = timeOption.getLongValue();

		Response response = new Response(CHANGED);
		final String timestamp = format(time, ChronoUnit.MILLIS);
		final String domain = info.domain;
		S3ProxyClient s3Client = s3Clients.getProxyClient(domain);
		StringBuilder log = new StringBuilder();
		String position = null;

		LOGGER.info("S3: {}, {}", domain, s3Client.getExternalEndpoint());
		String writeExpanded = replaceVars(write, timestamp);
		if (format == TEXT_PLAIN && updateSeries) {
			String[] lines = request.getPayloadString().split("[\\n\\r]+");
			for (String line : lines) {
				if (line.startsWith("!")) {
					line = line.substring(1);
					log.append(line).append(',');
				}
			}
		}
		StringUtil.truncateTail(log, ",");
		request.setProtectFromOffload();
		Series series = null;
		String acl = S3ProxyRequest.getAcl(request, s3Client.getAcl());
		boolean visible = acl != null && acl.startsWith("public-");

		Resource deviceDomain = domains.get(info.domain);
		if (!(deviceDomain instanceof DeviceDomain)) {
			deviceDomain = new DeviceDomain(info.domain, minutes, maxDevices);
			Resource previous = domains.putIfAbsent(info.domain, deviceDomain);
			if (previous != null) {
				deviceDomain = previous;
			} else {
				deviceDomain.setParent(this);
			}
		}
		if (deviceDomain instanceof DeviceDomain) {
			LeastRecentlyUpdatedCache<String, Resource> keptPosts = ((DeviceDomain) deviceDomain).keptPosts;
			WriteLock lock = keptPosts.writeLock();
			lock.lock();
			try {
				Device device;
				Resource child = keptPosts.update(info.name);
				if (child instanceof Device) {
					device = (Device) child;
				} else {
					device = new Device(info.name);
				}
				device.setVisible(visible);
				device.setPost(request, position, time, writeExpanded);
				// workaround for javascript dependency on "series-" file
				series = device.appendSeries(log.toString(), timestamp);
				if (device.getParent() == null) {
					device.setParent(deviceDomain);
					keptPosts.put(info.name, device);
				}
				LOGGER.info("Domain: {}, {} devices", info.domain, keptPosts.size());
			} finally {
				lock.unlock();
			}
		}

		MultiConsumer<Response> multi = new MultiConsumer<Response>() {

			@Override
			public void complete(Map<String, Response> results) {
				Response read = results.get("read");
				Response write = results.get("write");
				Response forward = results.get("forward");
				Response response = write != null ? write : read;
				if (forward != null) {
					if (response == null || (forward.isSuccess() && !forward.getPayloadString().equals("ack")
							&& !forward.getPayloadString().equals(""))) {
						exchange.respond(forward);
						return;
					}
					response.getOptions().addOtherOption(new ForwardResponseOption(forward.getCode()));
				}
				if (write != null && read != null) {
					if (write.getCode() == CHANGED && read.getCode() == CONTENT) {
						// Add get response
						OptionSet options = write.getOptions();
						options.setContentFormat(read.getOptions().getContentFormat());
						for (byte[] etag : read.getOptions().getETags()) {
							options.addOtherOption(ReadEtagOption.DEFINITION.create(etag));
						}
						write.setPayload(read.getPayload());
					}
					write.getOptions().addOtherOption(new ReadResponseOption(read.getCode()));
					exchange.respond(write);
				} else if (write != null) {
					exchange.respond(write);
				} else if (read != null) {
					exchange.respond(read);
				} else {
					response = new Response(INTERNAL_SERVER_ERROR);
					response.setPayload("no internal response!");
					exchange.respond(response);
				}
			}
		};

		URI httpDestinationUri;
		if (forward && httpForward != null && httpDestination != null
				&& ((httpDestinationUri = httpDestination.getDestination(domain)) != null)) {
			String authentication = httpDestination.getAuthentication(domain);
			DeviceIdentityMode deviceIdentificationMode = httpDestination.getDeviceIdentityMode(domain);
			Request outgoing = new Request(request.getCode(), request.getType());
			outgoing.setOptions(request.getOptions());
			byte[] payload = request.getPayload();
			if (deviceIdentificationMode == DeviceIdentityMode.HEADLINE) {
				byte[] head = (info.name + StringUtil.lineSeparator()).getBytes(StandardCharsets.UTF_8);
				payload = Bytes.concatenate(head, payload);
			} else if (deviceIdentificationMode == DeviceIdentityMode.QUERY_PARAMETER) {
				String path = httpDestinationUri.getPath();
				String query = httpDestinationUri.getQuery();
				if (query == null) {
					query = "id=" + info.name;
				} else {
					query = query + "&" + "id=" + info.name;
				}
				try {
					httpDestinationUri = httpDestinationUri.resolve(new URI(null, null, null, -1, path, query, null));
				} catch (URISyntaxException e) {
					LOGGER.warn("URI: ", e);
				}
			}
			outgoing.setPayload(payload);

			LOGGER.info("HTTP: {} => {} {} {} bytes", info, httpDestinationUri, deviceIdentificationMode,
					outgoing.getPayloadSize());
			final Consumer<Response> consumer = multi.create("forward");

			httpForward.handleForward(httpDestinationUri, authentication, outgoing, new ResponseConsumer() {

				@Override
				public void respond(Response response) {
					consumer.accept(response);
				}

			});
		}

		if (read != null && !read.isEmpty()) {
			List<Option> readEtag = request.getOptions().getOthers(ReadEtagOption.DEFINITION);
			S3ProxyRequest s3ReadRequest = S3ProxyRequest.builder(request).pathPrincipalIndex(1).subPath(read)
					.etags(readEtag).build();
			s3Client.get(s3ReadRequest, multi.create("read"));
		}

		if (writeExpanded != null && !writeExpanded.isEmpty()) {
			final Consumer<Response> putResponseConsumer = multi.create("write");

			Builder builder = S3ProxyRequest.builder(request).pathPrincipalIndex(1).subPath(writeExpanded);
			if (write.equals(writeExpanded)) {
				builder.timestamp(time);
			}
			s3Client.put(builder.build(), new Consumer<Response>() {

				@Override
				public void accept(Response response) {
					// respond with time?
					final TimeOption responseTimeOption = timeOption.adjust();
					if (responseTimeOption != null) {
						response.getOptions().addOtherOption(responseTimeOption);
					}
					putResponseConsumer.accept(response);
					if (response.isSuccess()) {
						LOGGER.info("Device {} updated!{}", info, visible ? " (public)" : " (private)");
					} else {
						LOGGER.info("Device {} update failed!", info);
					}
				}
			});
		}

		if (series != null) {
			updateSeries(request, series, s3Client);
		}
		if (multi.created()) {
			return;
		}
		// respond with time?
		final TimeOption responseTimeOption = timeOption.adjust();
		if (responseTimeOption != null) {
			response.getOptions().addOtherOption(responseTimeOption);
		}
		exchange.respond(response);
	}

	private void updateSeries(Request request, Series series, S3ProxyClient s3Client) {
		String content;
		String subResouce;
		synchronized (series) {
			content = series.getContent();
			subResouce = series.getS3Link();
		}
		if (content != null) {
			S3ProxyRequest s3SeriesRequest = S3ProxyRequest.builder(request).pathPrincipalIndex(1).subPath(subResouce)
					.content(content.getBytes(StandardCharsets.UTF_8)).contentType("text/plain; charset=utf-8").build();
			s3Client.put(s3SeriesRequest, S3AsyncProxyClient.NOP);
		}
	}

	private static abstract class MultiConsumer<T> {

		private boolean created;
		private Map<String, T> results = new HashMap<>();

		public Consumer<T> create(final String tag) {
			synchronized (results) {
				if (results.containsKey(tag)) {
					throw new IllegalArgumentException(tag + " already used!");
				}
				results.put(tag, null);
			}
			return new Consumer<T>() {

				@Override
				public void accept(T t) {
					boolean ready = false;
					synchronized (results) {
						results.put(tag, t);
						ready = created && !results.containsValue(null);
					}
					if (ready) {
						complete(results);
					}
				}
			};
		}

		public boolean created() {
			boolean ready = false;
			synchronized (results) {
				if (results.isEmpty()) {
					return false;
				}
				created = true;
				ready = !results.containsValue(null);
			}
			if (ready) {
				complete(results);
			}
			return true;
		}

		abstract public void complete(Map<String, T> results);
	}

	/**
	 * Resource representing a device domain
	 */
	public static class DeviceDomain extends CoapResource {

		private final LeastRecentlyUpdatedCache<String, Resource> keptPosts;

		private DeviceDomain(String name, long minutes, int maxDevices) {
			super(name, false);
			int minDevices = maxDevices / 10;
			if (minDevices < 100) {
				minDevices = maxDevices;
			}
			keptPosts = new LeastRecentlyUpdatedCache<>(minDevices, maxDevices, minutes, TimeUnit.MINUTES);
		}

		@Override
		public void add(Resource child) {
			throw new UnsupportedOperationException("Not supported!");
		}

		@Override
		public boolean delete(Resource child) {
			throw new UnsupportedOperationException("Not supported!");
		}

		@Override
		public Resource getChild(String name) {
			return keptPosts.get(name);
		}

		@Override // should be used for read-only
		public Collection<Resource> getChildren() {
			return keptPosts.values();
		}
	}

	/**
	 * Resource representing devices
	 */
	public static class Device extends ProtectedCoapResource {

		private Series series = null;
		private volatile Request post;
		private volatile long time;

		private Device(String name) {
			super(name);
			setObservable(true);
		}

		private void setPost(Request post, String position, long time, String write) {
			synchronized (this) {
				long previousTime = this.time;

				this.post = post;
				this.time = time;

				ResourceAttributes attributes = new ResourceAttributes(getAttributes());
				attributes.clearContentType();
				if (post.getOptions().hasContentFormat()) {
					attributes.addContentType(post.getOptions().getContentFormat());
				}
				attributes.clearAttribute(ATTRIBUTE_S3_LINK);
				if (write != null) {
					attributes.addAttribute(ATTRIBUTE_S3_LINK, "/" + write);
				}
				String timestamp = format(time, ChronoUnit.SECONDS);
				if (previousTime > 0 && time > 0) {
					long interval = TimeUnit.MILLISECONDS.toSeconds(time - previousTime);
					if (interval > 0) {
						long minutes = TimeUnit.SECONDS.toMinutes(interval + 5);
						if (minutes > 0) {
							timestamp += " (" + minutes + "min.)";
						} else {
							timestamp += " (" + interval + "sec.)";
						}
					}
				}
				attributes.setAttribute(ATTRIBUTE_TIME, timestamp);
				if (position != null && !position.isEmpty()) {
					attributes.setAttribute(ATTRIBUTE_POSITION, position);
				} else {
					attributes.clearAttribute(ATTRIBUTE_POSITION);
				}
				setAttributes(attributes);
			}
			changed();
		}

		private Series appendSeries(String values, String timestamp) {
			Series series = null;
			synchronized (this) {
				if (this.series != null) {
					if (!this.series.append(values, timestamp)) {
						delete(this.series);
						this.series = null;
					}
				}
				if (this.series == null) {
					this.series = new Series(timestamp);
					this.series.append(values, timestamp);
					add(this.series);
				}
				series = this.series;
				series.setVisible(isVisible());
			}
			return series;
		}

		@Override
		protected ResponseCode checkOperationPermission(PrincipalInfo info, Exchange exchange, boolean write) {
			if (!isVisible() && !getName().equals(info.name)) {
				return FORBIDDEN;
			}
			return null;
		}

		@Override
		public void handleGET(CoapExchange exchange) {
			Request devicePost = post;
			int format = devicePost.getOptions().getContentFormat();
			int accept = exchange.getRequestOptions().getAccept();
			if (accept == UNDEFINED) {
				accept = format == UNDEFINED ? APPLICATION_OCTET_STREAM : format;
			} else if (format == UNDEFINED) {
				if (accept != TEXT_PLAIN && accept != APPLICATION_OCTET_STREAM) {
					exchange.respond(NOT_ACCEPTABLE);
					return;
				}
			} else if (accept != format) {
				exchange.respond(NOT_ACCEPTABLE);
				return;
			}
			Response response = new Response(CONTENT);
			response.setPayload(devicePost.getPayload());
			response.getOptions().setContentFormat(accept);
			exchange.respond(response);
		}

		@Override
		public String toString() {
			return getName();
		}
	}

	public static class Series extends ProtectedCoapResource {

		private final String startDate;
		private final String s3Link;
		private final StringBuilder content = new StringBuilder();

		private Series(String timestamp) {
			super(SUB_RESOURCE_NAME);
			this.startDate = timestamp;
			this.s3Link = SUB_RESOURCE_NAME + "-" + timestamp;
			getAttributes().setAttribute(ATTRIBUTE_S3_LINK, "-" + timestamp);
		}

		@Override
		public void setParent(Resource parent) {
			super.setParent(parent);
			synchronized (this) {
				ResourceAttributes attributes = new ResourceAttributes(getAttributes());
				if (parent != null) {
					attributes.setTitle(parent.getName() + " => " + SUB_RESOURCE_NAME);
				} else {
					attributes.clearTitle();
				}
				setAttributes(attributes);
			}
		}

		private Device getDevice() {
			return (Device) getParent();
		}

		private String getS3Link() {
			return s3Link;
		}

		private boolean append(String values, String timestamp) {
			synchronized (this) {
				int len = content.length();
				String line = timestamp + ": ";
				if (values != null && !values.isEmpty()) {
					line += values;
				}
				boolean swap = len + line.length() > SERIES_MAX_SIZE;
				if (swap || !startDate.regionMatches(0, timestamp, 0, 11)) {
					return false;
				}
				if (len > 0) {
					if (content.charAt(len - 1) != '\n') {
						content.append('\n');
					}
				}
				content.append(line);
				return true;
			}
		}

		private String getContent() {
			return content.toString();
		}

		@Override
		protected ResponseCode checkOperationPermission(PrincipalInfo info, Exchange exchange, boolean write) {
			return getDevice().checkOperationPermission(info, exchange, write);
		}

		public void handleGET(CoapExchange exchange) {
			int accept = exchange.getRequestOptions().getAccept();
			if (accept != UNDEFINED && accept != TEXT_PLAIN) {
				exchange.respond(NOT_ACCEPTABLE);
				return;
			}
			Response response = new Response(CONTENT);
			response.setPayload(content.toString());
			response.getOptions().setContentFormat(TEXT_PLAIN);
			exchange.respond(response);
		}

	}

	private static String format(long millis, ChronoUnit unit) {
		Instant instant = Instant.ofEpochMilli(millis).truncatedTo(unit);
		String time = DateTimeFormatter.ISO_INSTANT.format(instant);
		if (unit == ChronoUnit.MILLIS && instant.getNano() == 0) {
			// ISO_INSTANT doesn't use .000Z
			time = time.substring(0, time.length() - 1) + ".000Z";
		}
		return time;
	}

	/**
	 * Replace supported variables.
	 * 
	 * {@code ${now}}, {@code ${date}}, and {@code ${time}} are replaced with
	 * the current timestamp, either device time, if the device supports the
	 * {@link TimeOption}, or the server systemtime.
	 * 
	 * <pre>
	 * e.g.: 2022-11-05T17:03:41.615Z
	 * now := 2022-11-05T17:03:41.615Z
	 * date := 2022-11-05
	 * time := 17:03:41.615
	 * </pre>
	 * 
	 * @param value value with variables to replace
	 * @param timestamp timestamp
	 * @return value with replaced variables
	 */
	private String replaceVars(String value, String timestamp) {
		// 2022-11-05T17:03:41.615Z
		if (value != null && !value.isEmpty()) {
			value = value.replaceAll("(?<!\\$)\\$\\{now\\}", timestamp);
			value = value.replaceAll("(?<!\\$)\\$\\{date\\}", timestamp.substring(0, 10));
			value = value.replaceAll("(?<!\\$)\\$\\{time\\}", timestamp.substring(11, timestamp.length() - 1));
		}
		return value;
	}
}
