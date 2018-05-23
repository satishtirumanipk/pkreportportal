/*
 * Copyright 2016 EPAM Systems
 * 
 * 
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/service-gateway
 * 
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.epam.reportportal.gateway;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;

/**
 * Shares information about component versions
 *
 * @author Andrei Varabyeu
 */
@RestController
public class CompositeInfoEndpoint {

	private static final Logger LOGGER = LoggerFactory.getLogger(CompositeInfoEndpoint.class);

	private final DiscoveryClient discoveryClient;

	private final EurekaClient eurekaClient;

	private final RestTemplate loadBalancedRestTemplate;

	private final RestTemplate restTemplate;

	@SuppressWarnings("SpringJavaAutowiringInspection")
	@Autowired
	public CompositeInfoEndpoint(RestTemplate loadBalancedRestTemplate, DiscoveryClient discoveryClient, EurekaClient eurekaClient) {
		this.restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(
				HttpClientBuilder.create().setSSLHostnameVerifier((s, sslSession) -> true).build()));

		this.loadBalancedRestTemplate = loadBalancedRestTemplate;
		this.discoveryClient = discoveryClient;
		this.eurekaClient = eurekaClient;
	}

	@RequestMapping(value = "/composite/{endpoint}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
	@ResponseBody
	public Map<String, ?> compose(@PathVariable("endpoint") String endpoint) {
		return discoveryClient.getServices().stream().map((Function<String, AbstractMap.SimpleImmutableEntry<String, Object>>) service -> {
			try {
				List<ServiceInstance> instances = discoveryClient.getInstances(service);
				if (instances.isEmpty()) {
					return new AbstractMap.SimpleImmutableEntry<>(service, "DOWN");
				}
				ServiceInstance instance = instances.get(0);
				String protocol = instance.isSecure() ? "https" : "http";
				HttpHeaders headers = new HttpHeaders();
				headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
				return new AbstractMap.SimpleImmutableEntry<>(instance.getServiceId(), loadBalancedRestTemplate
						.exchange(protocol + "://{service}/{endpoint}", HttpMethod.GET, new HttpEntity<>(null, headers), Map.class,
								instance.getServiceId(), endpoint).getBody());
			} catch (Exception e) {
				return new AbstractMap.SimpleImmutableEntry<>(service, "DOWN");
			}
		}).collect(toMap(AbstractMap.SimpleImmutableEntry::getKey, AbstractMap.SimpleImmutableEntry::getValue, (value1, value2) -> value2));

	}

	@RequestMapping(value = "/composite/info", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
	@ResponseBody
	public Map<String, ?> composeInfos() {
		return composeInfo();

	}

	@RequestMapping(value = "/composite/health", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
	@ResponseBody
	public Map<String, ?> composeHealth() {
		return eurekaClient.getApplications().getRegisteredApplications().stream().flatMap(app -> app.getInstances().stream())
				.collect(Collectors.toMap(InstanceInfo::getAppName, InstanceInfo::getStatus));

	}

	private Map<String, ?> composeInfo() {
		return eurekaClient.getApplications().getRegisteredApplications().stream().flatMap(app -> app.getInstances().stream())
				.map(instanceInfo -> {
					try {
						HttpHeaders headers = new HttpHeaders();
						headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
						return new AbstractMap.SimpleImmutableEntry<>(instanceInfo.getAppName(), restTemplate
								.exchange(instanceInfo.getStatusPageUrl(), HttpMethod.GET, new HttpEntity<>(null, headers), Map.class)
								.getBody());
					} catch (Exception e) {
						LOGGER.error("Unable to obtain service info", e);
						return new AbstractMap.SimpleImmutableEntry<>(instanceInfo.getAppName(), "DOWN");
					}
				}).collect(toMap(AbstractMap.SimpleImmutableEntry::getKey, AbstractMap.SimpleImmutableEntry::getValue,
						(value1, value2) -> value2));
	}
}
