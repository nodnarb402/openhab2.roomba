/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.irobot.handler;

import static org.openhab.binding.irobot.IRobotBindingConstants.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.json.JSONArray;
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
public class RoombaHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(RoombaHandler.class);
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private static final int reconnectDelay = 5; // In seconds
    private @Nullable Future<?> reconnectReq;
    private RoombaConfiguration config;
    private String blid = null;
    protected RoombaMqttBrokerConnection connection;
    private Hashtable<String, State> lastState = new Hashtable<String, State>();
    private JSONObject lastSchedule = null;
    private Boolean isPaused = false;

    public RoombaHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.trace("initialize()");
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
                connection.stop();
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

        if (ch.equals(CHANNEL_COMMAND)) {
            if (command instanceof StringType) {
                String cmd = command.toString();

                if (cmd.equals(CMD_CLEAN)) {
                    cmd = isPaused ? "resume" : "start";
                }

                JSONObject request = new JSONObject();

                request.put("command", cmd);
                request.put("time", System.currentTimeMillis() / 1000);
                request.put("initiator", "localApp");

                sendRequest("cmd", request);
            }
        } else if (ch.startsWith(CHANNEL_SCHED_SWITCH_PREFIX)) {
            JSONObject schedule = lastSchedule;

            // Schedule can only be updated in a bulk, so we have to store current
            // schedule and modify components.
            if (command instanceof OnOffType && schedule != null && schedule.has("cycle")) {
                for (int i = 0; i < CHANNEL_SCHED_SWITCH.length; i++) {
                    if (ch.equals(CHANNEL_SCHED_SWITCH[i])) {
                        JSONArray cycle = schedule.getJSONArray("cycle");
                        cycle.put(i, command.equals(OnOffType.ON) ? "start" : "none");

                        sendDelta("cleanSchedule", schedule);
                        break;
                    }
                }
            }
        }
    }

    private void sendDelta(String key, JSONObject data) {
        // Huge thanks to Dorita980 author(s) for an insight on this
        JSONObject state = new JSONObject();
        state.put(key, data);
        JSONObject request = new JSONObject();
        request.put("state", state);

        logger.trace("Sending delta: {}", request.toString());
        sendRequest("delta", request);
    }

    private void sendRequest(String topic, JSONObject request) {
        connection.publish(topic, request.toString().getBytes());
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
                connection = new RoombaMqttBrokerConnection(config.ipaddress, blid, this);
                connection.start(blid, config.password);

            } catch (Exception e) {
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

    public void onConnected() {
        updateStatus(ThingStatus.ONLINE);
    }

    public void onDisconnected(Throwable error) {
        String message = error.getMessage();

        logger.error("MQTT connection failed: {}", message);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        scheduleReconnect();
    }

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
                String cycle = missionStatus.getString("cycle");
                String phase = missionStatus.getString("phase");
                String command;

                if (cycle.equals("none")) {
                    command = CMD_STOP;
                } else {
                    switch (phase) {
                        case "stop":
                        case "stuck": // CHECKME: could also be equivalent to "stop" command
                        case "pause": // Never observed in Roomba 930
                            command = CMD_PAUSE;
                            break;
                        case "hmUsrDock":
                        case "dock": // Never observed in Roomba 930
                            command = CMD_DOCK;
                            break;
                        default:
                            command = cycle; // "clean" or "spot"
                            break;
                    }
                }

                isPaused = command.equals(CMD_PAUSE);

                reportString(CHANNEL_CYCLE, cycle);
                reportString(CHANNEL_PHASE, phase);
                reportString(CHANNEL_COMMAND, command);
                reportString(CHANNEL_ERROR, String.valueOf(missionStatus.getInt("error")));
            }

            if (reported.has("batPct")) {
                reportInt(CHANNEL_BATTERY, reported.getInt("batPct"));
            }

            if (reported.has("bin")) {
                JSONObject bin = reported.getJSONObject("bin");
                String binStatus;

                // The bin cannot be both full and removed simultaneously, so let's
                // encode it as a single value
                if (!bin.getBoolean("present")) {
                    binStatus = BIN_REMOVED;
                } else if (bin.getBoolean("full")) {
                    binStatus = BIN_FULL;
                } else {
                    binStatus = BIN_OK;
                }

                reportString(CHANNEL_BIN, binStatus);
            }

            if (reported.has("signal")) {
                // {"signal":{"rssi":-55,"snr":33}}
                JSONObject signal = reported.getJSONObject("signal");

                reportInt(CHANNEL_RSSI, signal.getInt("rssi"));
                reportInt(CHANNEL_SNR, signal.getInt("snr"));
            }

            if (reported.has("cleanSchedule")) {
                // "cleanSchedule":{"cycle":["none","start","start","start","start","none","none"],"h":[9,12,12,12,12,12,9],"m":[0,0,0,0,0,0,0]}
                JSONObject schedule = reported.getJSONObject("cleanSchedule");

                if (schedule.has("cycle")) {
                    JSONArray cycle = schedule.getJSONArray("cycle");

                    for (int i = 0; i < cycle.length(); i++) {
                        reportSwitch(CHANNEL_SCHED_SWITCH[i], cycle.getString(i).equals("start"));
                    }
                }

                lastSchedule = schedule;
            }

            // {"navSwVer":"01.12.01#1","wifiSwVer":"20992","mobilityVer":"5806","bootloaderVer":"4042","umiVer":"6","softwareVer":"v2.4.6-3","tz":{"events":[{"dt":1583082000,"off":180},{"dt":1619884800,"off":180},{"dt":0,"off":0}],"ver":8}}
            reportProperty(Thing.PROPERTY_FIRMWARE_VERSION, reported, "softwareVer");
            reportProperty(reported, "navSwVer");
            reportProperty(reported, "wifiSwVer");
            reportProperty(reported, "mobilityVer");
            reportProperty(reported, "bootloaderVer");
            reportProperty(reported, "umiVer");
        } catch (JSONException e) {
            logger.error("Failed to parse JSON message from {}: {}", config.ipaddress, e);
            logger.error("Raw contents: {}", payload);
        }
    }

    private void reportString(String channel, String str) {
        reportState(channel, StringType.valueOf(str));
    }

    private void reportInt(String channel, int n) {
        reportState(channel, new DecimalType(n));
    }

    private void reportSwitch(String channel, boolean s) {
        reportState(channel, OnOffType.from(s));
    }

    private void reportState(String channel, State value) {
        lastState.put(channel, value);
        updateState(channel, value);
    }

    private void reportProperty(JSONObject container, String attribute) {
        reportProperty(attribute, container, attribute);
    }

    private void reportProperty(String property, JSONObject container, String attribute) {
        if (container.has(attribute)) {
            updateProperty(property, container.getString(attribute));
        }
    }
}
