/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.irobot.handler;

import static org.openhab.binding.irobot.IRobotBindingConstants.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.io.transport.mqtt.MqttConnectionObserver;
import org.eclipse.smarthome.io.transport.mqtt.MqttConnectionState;
import org.eclipse.smarthome.io.transport.mqtt.MqttMessageSubscriber;
import org.eclipse.smarthome.io.transport.mqtt.reconnect.PeriodicReconnectStrategy;
import org.json.JSONException;
import org.json.JSONObject;
import org.openhab.binding.irobot.internal.IdentProtocol;
import org.openhab.binding.irobot.internal.IdentProtocol.IdentData;
import org.openhab.binding.irobot.internal.RawMQTT;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.openhab.binding.irobot.roomba.RoombaMqttBrokerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RoombaHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author hkuhn42 - Initial contribution
 */
public class RoombaHandler extends BaseThingHandler implements MqttConnectionObserver, MqttMessageSubscriber {

    private final Logger logger = LoggerFactory.getLogger(RoombaHandler.class);
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private static final int reconnectDelay = 5; // In seconds
    private @Nullable Future<?> reconnectReq;
    private RoombaConfiguration config;
    private String blid = null;
    protected RoombaMqttBrokerConnection connection;
    private Hashtable<String, State> lastState = new Hashtable<String, State>();

    public RoombaHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        config = getConfigAs(RoombaConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);
        connect();
    }

    @Override
    public void dispose() {
        logger.trace("dispose()");

        singleThread.execute(() -> {
            if (reconnectReq != null) {
                reconnectReq.cancel(false);
                reconnectReq = null;
            }

            if (connection != null) {
                connection.forceStop();
                connection = null;
            }
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String ch = channelUID.getId();
        if (command instanceof RefreshType) {
            State value = lastState.get(ch);

            if (value != null) {
                updateState(ch, value);
            }

            return;
        }
        switch (ch) {
            case CHANNEL_COMMAND:
                if (command instanceof StringType) {
                    JSONObject request = new JSONObject();

                    request.put("command", command.toString());
                    request.put("time", System.currentTimeMillis() / 1000);
                    request.put("initiator", "localApp");

                    connection.publish("cmd", request.toString().getBytes());
                }
                break;
        }
    }

    private void connect() {
        // In order not to mess up our connection state we need to make sure
        // that any two calls are never running concurrently. We use
        // singleThreadExecutorService for this purpose
        singleThread.execute(() -> {
            String error = null;

            logger.info("Connecting to " + config.ipaddress);

            try {
                InetAddress host = InetAddress.getByName(config.ipaddress);

                if (blid == null) {
                    DatagramSocket identSocket = IdentProtocol.sendRequest(host);
                    DatagramPacket identPacket = IdentProtocol.receiveResponse(identSocket);

                    identSocket.close();
                    IdentProtocol.IdentData ident;

                    try {
                        ident = new IdentProtocol.IdentData(identPacket);
                    } catch (JSONException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Malformed IDENT response");
                        return;
                    }

                    if (ident.ver < IdentData.MIN_SUPPORTED_VERSION) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Unsupported version " + ident.ver);
                        return;
                    }

                    if (!ident.product.equals(IdentData.PRODUCT_ROOMBA)) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Not a Roomba: " + ident.product);
                        return;
                    }

                    blid = ident.blid;
                }

                logger.debug("BLID is: " + blid);

                if (!config.havePassword()) {
                    RawMQTT mqtt;

                    try {
                        mqtt = new RawMQTT(host, 8883);
                    } catch (KeyManagementException | NoSuchAlgorithmException e1) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e1.toString());
                        return; // This is internal system error, no retry
                    }

                    mqtt.requestPassword();
                    RawMQTT.Packet response = mqtt.readPacket();
                    mqtt.close();

                    if (response != null && response.isValidPasswdPacket()) {
                        RawMQTT.PasswdPacket passwdPacket = new RawMQTT.PasswdPacket(response);

                        config.password = passwdPacket.getPassword();
                        if (config.havePassword()) {
                            Configuration configuration = editConfiguration();

                            configuration.put(RoombaConfiguration.FIELD_PASSWORD, config.password);
                            updateConfiguration(configuration);
                        }
                    }
                }

                if (!config.havePassword()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "Authentication on the robot is required");
                    scheduleReconnect();
                    return;
                }

                logger.debug("Password is: " + config.password);

                // BLID is used as both client ID and username. The name of BLID also came from Roomba980-python
                connection = new RoombaMqttBrokerConnection(config.ipaddress, blid);
                connection.setCredentials(blid, config.password);
                connection.setTrustManagers(RawMQTT.getTrustManagers());
                // MQTT connection reconnects itself, so we don't have to call scheduleReconnect()
                // when it breaks. Just set the period in ms.
                connection.setReconnectStrategy(
                        new PeriodicReconnectStrategy(reconnectDelay * 1000, reconnectDelay * 1000));
                connection.start().exceptionally(e -> {
                    connectionStateChanged(MqttConnectionState.DISCONNECTED, e);
                    return false;
                }).thenAccept(v -> {
                    if (!v) {
                        connectionStateChanged(MqttConnectionState.DISCONNECTED, new TimeoutException("Timeout"));
                    } else {
                        connectionStateChanged(MqttConnectionState.CONNECTED, null);
                    }
                });

            } catch (UnknownHostException e) {
                error = e.toString();
            } catch (IOException e) {
                error = e.toString();
            }

            if (error != null) {
                logger.error(error);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, error);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        reconnectReq = scheduler.schedule(() -> {
            connect();
        }, reconnectDelay, TimeUnit.SECONDS);
    }

    @Override
    public void connectionStateChanged(MqttConnectionState state, @Nullable Throwable error) {
        if (state == MqttConnectionState.CONNECTED) {
            logger.debug("Connection established");
            updateStatus(ThingStatus.ONLINE);

            // Roomba sends us two topics:
            // "wifistat" - reports singnal strength and current robot position
            // "$aws/things/<BLID>/shadow/update" - the rest of messages
            // Subscribe to everything since we're interested in both
            connection.subscribe("#", this).exceptionally(e -> {
                logger.error("Subscription failed: " + e.getMessage());
                return false;
            }).thenAccept(v -> {
                if (!v) {
                    logger.error("Subscription timeout");
                } else {
                    logger.trace("Subscription done");
                }
            });

        } else {
            // channelStateByChannelUID.values().forEach(c -> c.stop());
            String message;

            if (error != null) {
                message = error.getMessage();
                logger.error("MQTT connection failed: " + message);
            } else {
                message = null;
                logger.error("MQTT connection failed for unspecified reason");
            }

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        }
    }

    @Override
    public void processMessage(String topic, byte[] payload) {
        String jsonStr = new String(payload);
        logger.debug("Got topic {} data {}", topic, jsonStr);

        try {
            // Data comes as JSON string: {"state":{"reported":<Actual content here>}}
            // or: {"state":{"desired":<Some content here>}}
            // Of the second form i've so far observed only: {"state":{"desired":{"echo":null}}}
            // I don't know what it is, so let's ignore it.
            // Examples of the first form are given below, near the respective parsing code
            JSONObject state = new JSONObject(jsonStr).getJSONObject("state");

            if (!state.has("reported")) {
                return;
            }

            JSONObject reported = state.getJSONObject("reported");

            if (reported.has("cleanMissionStatus")) {
                // {"cleanMissionStatus":{"cycle":"clean","phase":"hmUsrDock","expireM":0,"rechrgM":0,"error":0,"notReady":0,"mssnM":1,"sqft":7,"initiator":"rmtApp","nMssn":39}}
                JSONObject missionStatus = reported.getJSONObject("cleanMissionStatus");

                reportString(CHANNEL_CYCLE, missionStatus, "cycle");
                reportString(CHANNEL_PHASE, missionStatus, "phase");
            }

            if (reported.has("lastCommand")) {
                // {"lastCommand":{"command":"start","time":1590519853,"initiator":"rmtApp"}}
                JSONObject command = reported.getJSONObject("lastCommand");

                reportString(CHANNEL_COMMAND, command, "command");
            }

            if (reported.has("signal")) {
                // {"signal":{"rssi":-55,"snr":33}}
                JSONObject signal = reported.getJSONObject("signal");

                reportInt(CHANNEL_RSSI, signal, "rssi");
                reportInt(CHANNEL_SNR, signal, "snr");
            }
        } catch (JSONException e) {
            logger.error("Failed to parse JSON message from {}: {}", config.ipaddress, e);
            logger.error("Raw contents: {}", payload);
        }
    }

    private void reportString(String channel, JSONObject container, String attr) {
        StringType value = StringType.valueOf(container.getString(attr));

        lastState.put(channel, value);
        updateState(channel, value);
    }

    private void reportInt(String channel, JSONObject container, String attr) {
        DecimalType value = new DecimalType(container.getInt(attr));

        lastState.put(channel, value);
        updateState(channel, value);
    }
}
