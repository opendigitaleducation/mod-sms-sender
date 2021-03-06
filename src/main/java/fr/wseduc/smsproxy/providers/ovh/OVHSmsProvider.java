/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.smsproxy.providers.ovh;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import fr.wseduc.smsproxy.providers.ovh.OVHHelper.OVHClient;
import fr.wseduc.smsproxy.providers.ovh.OVHHelper.OVH_ENDPOINT;
import fr.wseduc.smsproxy.providers.SmsProvider;

public class OVHSmsProvider extends SmsProvider{

	private OVHClient ovhRestClient;
	private String AK, AS, CK, endPoint;

	@Override
	public void initProvider(Vertx vertx, JsonObject config) {
		this.AK = config.getString("applicationKey", "");
		this.AS = config.getString("applicationSecret", "");
		this.CK = config.getString("consumerKey", "");
		this.endPoint = config.getString("ovhEndPoint", OVH_ENDPOINT.ovh_eu.getValue());

		ovhRestClient = new OVHClient(vertx, endPoint, AK, AS, CK);
	}

	private void retrieveSmsService(final Message<JsonObject> message, final Handler<String> callBack){
		ovhRestClient.get("/sms/", new JsonObject(), new Handler<HttpClientResponse>() {
			public void handle(final HttpClientResponse response) {
				logger.debug("[OVH][retrieveSmsService] /sms/ call returned : "+response);
				if(response == null){
					logger.error("[OVH][retrieveSmsService] /sms/ call response is null.");
					sendError(message, "ovh.apicall.error", null);
					return;
				}
				response.bodyHandler(new Handler<Buffer>() {
					public void handle(Buffer body) {
						if(response.statusCode() == 200){
							logger.debug("[OVH][retrieveSmsService] Ok with body : "+body);
							JsonArray smsServices = new JsonArray(body.toString("UTF-8"));
							callBack.handle(smsServices.getString(0));
						} else {
							logger.error("[OVH][retrieveSmsService] /sms/ reponse code ["+response.statusCode()+"] : "+body.toString("UTF-8"));
							sendError(message, body.toString("UTF-8"), null);
						}
					}
				});
			}
		});
	}

	@Override
	public void sendSms(final Message<JsonObject> message) {
		final JsonObject parameters = message.body().getJsonObject("parameters");
		logger.debug("[OVH][sendSms] Called with parameters : "+parameters);

		final Handler<HttpClientResponse> resultHandler = new Handler<HttpClientResponse>() {
			public void handle(HttpClientResponse response) {
				if(response == null){
					sendError(message, "ovh.apicall.error", null);
				} else {
					response.bodyHandler(new Handler<Buffer>(){
						public void handle(Buffer body) {
							final JsonObject response = new JsonObject(body.toString());
							final JsonArray invalidReceivers = response.getJsonArray("invalidReceivers", new JsonArray());
							final JsonArray validReceivers = response.getJsonArray("validReceivers", new JsonArray());

							if(validReceivers.size() == 0){
								sendError(message, "invalid.receivers.all", null, new JsonObject(body.toString()));
							} else if(invalidReceivers.size() > 0){
								sendError(message, "invalid.receivers.partial", null, new JsonObject(body.toString()));
							} else {
								message.reply(response);
							}
						}
					});
				}
			}
		};

		Handler<String> serviceCallback = new Handler<String>() {
			public void handle(String service) {
				if(service == null){
					sendError(message, "ovh.apicall.error", null);
				} else {
					ovhRestClient.post("/sms/"+service+"/jobs/", parameters, resultHandler);
				}
			}
		};

		retrieveSmsService(message, serviceCallback);
	}

	@Override
	public void getInfo(final Message<JsonObject> message) {
		final JsonObject parameters = message.body().getJsonObject("parameters");
		logger.debug("[OVH][getInfo] Called with parameters : "+parameters);

		retrieveSmsService(message, new Handler<String>() {
			public void handle(String service) {
				if(service == null){
					sendError(message, "ovh.apicall.error", null);
				} else {
					ovhRestClient.get("/sms/"+service, parameters, new Handler<HttpClientResponse>() {
						public void handle(HttpClientResponse response) {
							if(response == null){
								sendError(message, "ovh.apicall.error", null);
								return;
							}
							response.bodyHandler(new Handler<Buffer>(){
								public void handle(Buffer body) {
									final JsonObject response = new JsonObject(body.toString());
									message.reply(response);
								}
							});
						}
					});
				}
			}
		});
	}

}
