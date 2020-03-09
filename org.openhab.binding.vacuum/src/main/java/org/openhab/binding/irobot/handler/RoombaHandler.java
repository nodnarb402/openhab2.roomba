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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.json.JSONException;
import org.openhab.binding.irobot.internal.IdentProtocol;
import org.openhab.binding.irobot.internal.IdentProtocol.IdentData;
import org.openhab.binding.irobot.internal.RawMQTT;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RoombaHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author hkuhn42 - Initial contribution
 */
public class RoombaHandler extends BaseThingHandler {

    private static final byte[] passwdRequest = { (byte) 0xf0, 0x05, (byte) 0xef, (byte) 0xcc, 0x3b, 0x29, 0x00 };
    private final Logger logger = LoggerFactory.getLogger(RoombaHandler.class);
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private @Nullable Future<?> reconnectReq;
    private RoombaConfiguration config;
    private String blid = null;

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
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (channelUID.getId().equals(CLEAN)) {

        }
        if (channelUID.getId().equals(DOCK)) {

        }
        if (channelUID.getId().equals(SPOT)) {

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
        }, 5, TimeUnit.SECONDS);
    }
}
