/*******************************************************************************
 * Copyright (c) 2021 Bosch IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch IO GmbH - initial creation
 ******************************************************************************/
package org.eclipse.californium.core.network.stack.congestioncontrol;

import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.elements.util.CounterStatisticManager;
import org.eclipse.californium.elements.util.SimpleCounterStatistic;
import org.eclipse.californium.elements.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Congestion statistic implementation using counter and logging for result.
 * 
 * @since 3.0
 */
public class CongestionStatisticLogger extends CounterStatisticManager {

	/** the logger. */
	private static final Logger LOGGER = LoggerFactory.getLogger(CongestionStatisticLogger.class);

	private final SimpleCounterStatistic sentRequests = new SimpleCounterStatistic("sent-requests", align);
	private final SimpleCounterStatistic queueRequests = new SimpleCounterStatistic("queue-requests", align);
	private final SimpleCounterStatistic dequeueRequests = new SimpleCounterStatistic("dequeue-requests", align);
	private final SimpleCounterStatistic receivedResponses = new SimpleCounterStatistic("recv-responses", align);

	/**
	 * Create passive congestion logger.
	 * 
	 * {@link #dump()} must be called externally.
	 * 
	 * @param tag logging tag
	 * @since 3.1 
	 */
	public CongestionStatisticLogger(String tag) {
		super(tag);
		init();
	}

	private void init() {
		add(sentRequests);
		add(queueRequests);
		add(receivedResponses);
	}

	@Override
	public boolean isEnabled() {
		return LOGGER.isInfoEnabled();
	}

	@Override
	public void dump() {
		try {
			if (isEnabled()) {
				if (LOGGER.isDebugEnabled()) {
					if (receivedResponses.isUsed() || sentRequests.isUsed() || queueRequests.isUsed()) {
						String eol = StringUtil.lineSeparator();
						String head = "   " + tag;
						StringBuilder log = new StringBuilder();
						log.append(tag).append("congestion statistic:").append(eol);
						log.append(head).append(sentRequests).append(eol);
						log.append(head).append(queueRequests).append(eol);
						log.append(head).append(dequeueRequests).append(eol);
						log.append(head).append(receivedResponses).append(eol);
						LOGGER.debug("{}", log);
					}
				}
				transferCounter();
			}
		} catch (Throwable e) {
			LOGGER.error("{}", tag, e);
		}
	}

	public void sendRequest() {
		sentRequests.increment();
	}

	public void queueRequest() {
		queueRequests.increment();
	}

	public void dequeueRequest() {
		dequeueRequests.increment();
	}

	public void receiveResponse(Response response) {
		if (!response.isDuplicate()) {
			receivedResponses.increment();
		}
	}

}
