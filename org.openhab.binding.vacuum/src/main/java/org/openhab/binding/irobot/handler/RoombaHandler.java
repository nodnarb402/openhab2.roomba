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

import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.irobot.roomba.CleanCommand;
import org.openhab.binding.irobot.roomba.TCPConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RoombaHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 * 
 * @author hkuhn42 - Initial contribution
 */
public class RoombaHandler extends BaseThingHandler {

    private Logger logger = LoggerFactory.getLogger(RoombaHandler.class);

	public RoombaHandler(Thing thing) {
		super(thing);
	}

	@Override
	public void handleCommand(ChannelUID channelUID, Command command) {
        try {
			if(channelUID.getId().equals(CLEAN)) {
				new CleanCommand().execute(new TCPConnection(String.valueOf(getThing().getConfiguration().get("ipaddress"))));
			}
			if(channelUID.getId().equals(DOCK)) {
			    
			}
			if(channelUID.getId().equals(SPOT)) {
			    
			}
		} 
        catch (IOException e) {
			e.printStackTrace();
		}
	}
}
