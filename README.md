# iRobot binding

This binding provides for integration of products by iRobot company (http://www.irobot.com/). It is currently developed to support Roomba 900
series robotic vacuum cleaner with built-in Wi-Fi module.

The development starts with an abandoned draft by hkunh42 (http://github.com/hkuhn42/openhab2.roomba) and will heavily use
Roomba980-Python project by Nick Waterton (http://github.com/NickWaterton/Roomba980-Python) as a reference. The goal is to
implement a binding that interfaces directly to the robot without a need for a dedicated MQTT server.

## Supported Things

- iRobot Roomba robotic vacuum cleaner (https://www.irobot.com/roomba). The binding has been developed and tested with Roomba 930.

## Discovery

Roombas on current network will be discovered automatically, however in order to connect to them a password is needed. The
password is a machine-generated string, which is unfortunately not exposed by the original iRobot smartphone application, but
it can be downloaded from the robot itself. If no password is configured, the Thing enters "CONFIGURATION PENDING" state.
Now you need to perform authorization by pressing and holding the HOME button on your robot until it plays series of tones
(approximately 2 seconds). The Wi-Fi indicator on the robot will flash for 30 seconds, the binding should automatically
receive the password and go ONLINE.

After you've done this procedure you can write the password somewhere in case if you need to reconfigure your binding. It's not
known, however, whether the password is eternal or can change during factory reset.

## Binding Configuration

There's no global configuration for this binding.

## Thing Configuration


| Parameter | Meaning                                |
|-----------|----------------------------------------|
| ipaddress | IP address (or hostname) of your robot |
| password  | Password for the robot                 |

## Channels

| channel | type   | description                                        | Read-only |
|---------|--------|----------------------------------------------------|-----------|
| command | String | Command to execute: clean, spot, dock, pause, stop | N |
| cycle   | String | Current mission: none, clean, spot                 | Y |
| phase   | String | Current phase of the mission; see below.           | Y |
| battery | Number | Battery charge in percents                         | Y |
| bin     | String | Bin status: ok, removed, full                      | Y |
| error   | String | Error code; see below                              | Y |
| rssi    | Number | Wi-Fi Received Signal Strength indicator in db     | Y |
| snr     | Number | Wi-Fi Signal to noise ratio                        | Y |

Known phase strings and their meanings:

| phase     | Meaning                           |
|-----------|-----------------------------------|
| charge    | Charging                          |
| new       | New Mission (*)                   |
| run       | Running                           |
| resume    | Resumed (*)                       |
| hmMidMsn  | Going for recharge during mission |
| recharge  | Recharging                        |
| stuck     | Stuck                             |
| mUsrDock  | Going home (on user command)      |
| dock      | Docking (*)                       |
| dockend   | Docking - End Mission (*)         |
| cancelled | Cancelled (*)                     |
| stop      | Stopped                           |
| pause     | Paused (*)                        |
| hmPostMsn | Going home after mission          |
| "" (empty string) | None (*)                  |

Phases, marked with asterisk (*), have not been seen being reported by Roomba 930. All the definitions
are taken from Roomba980-Python.

Error codes. Data type is string in order to be able to utilize mapping to human-readable strings.

| Code | Meaning                    |
|------|----------------------------|
| 0    | None                       |
| 1    | Left wheel off floor       |
| 2    | Main Brushes stuck    |
| 3    | Right wheel off floor      |
| 4    | Left wheel stuck    |
| 5    | Right wheel stuck    |
| 6    | Stuck near a cliff    |
| 7    | Left wheel error    |
| 8    | Bin error    |
| 9    | Bumper stuck    |
| 10    | Right wheel error    |
| 11    | Bin error    |
| 12    | Cliff sensor issue    |
| 13    | Both wheels off floor    |
| 14    | Bin missing    |
| 15    | Reboot required    |
| 16    | Bumped unexpectedly    |
| 17    | Path blocked    |
| 18    | Docking issue    |
| 19    | Undocking issue    |
| 20    | Docking issue    |
| 21    | Navigation problem    |
| 22    | Navigation problem    | 
| 23    | Battery issue    |
| 24    | Navigation problem    |
| 25    | Reboot required    |
| 26    | Vacuum problem    |
| 27    | Vacuum problem    |
| 29    | Software update needed    |
| 30    | Vacuum problem    |
| 31    | Reboot required    |
| 32    | Smart map problem    |
| 33    | Path blocked    |
| 34    | Reboot required    |
| 35    | Unrecognized cleaning pad |
| 36    | Bin full    |
| 37    | Tank needed refilling    |
| 38    | Vacuum problem    |
| 39    | Reboot required    |
| 40    | Navigation problem    |
| 41    | Timed out    |
| 42    | Localization problem    |
| 43    | Navigation problem    |
| 44    | Pump issue    |
| 45    | Lid open    |
| 46    | Low battery    |
| 47    | Reboot required    |
| 48    | Path blocked              |
| 52    | Pad required attention    |
| 65    | Hardware problem detected |
| 66    | Low memory                |
| 68    | Hardware problem detected |
| 73    | Pad type changed          |
| 74    | Max area reached          |
| 75    | Navigation problem        |
| 76    | Hardware problem detected |

NOTES:

1. Sending "pause" command during missions other than "clean" is equivalent to sending "stop"
2. Switching to "spot" mission is possible only in "stop" state. Attempt to do it otherwise causes error: the command is rejected
and error tones are played.
3. Roomba's built-in MQTT server, used for communication, supports only a single local connection at a time. Bear this in mind
when you want to do something that requires local connection from your phone, like reconfiguring the network. Disable OpenHAB
Thing before doing this.