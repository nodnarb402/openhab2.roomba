/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.irobot;

import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link VacuumBinding} class defines common constants, which are
 * used across the whole binding.
 *
 * @author hkuhn42 - Initial contribution
 */
public class IRobotBindingConstants {

    public static final String BINDING_ID = "irobot";

    // List of all Thing Type UIDs
    public final static ThingTypeUID THING_TYPE_ROOMBA = new ThingTypeUID(BINDING_ID, "roomba");

    // List of all Channel ids
    public final static String CHANNEL_COMMAND = "command";
    public final static String CHANNEL_CYCLE = "cycle";
    public final static String CHANNEL_PHASE = "phase";
    public final static String CHANNEL_RSSI = "rssi";
    public final static String CHANNEL_SNR = "snr";

    public final static String CMD_CLEAN = "clean";
    public final static String CMD_DOCK = "dock";
    public final static String CMD_PAUSE = "pause";
    public final static String CMD_STOP = "stop";
}
