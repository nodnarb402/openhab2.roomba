# iRobot binding

This binding provides for integration of products by iRobot company (http://www.irobot.com/). It is currently developed to support Roomba 900
series robotic vacuum cleaner with built-in Wi-Fi module.

The development starts with an abandoned draft by hkunh42 (http://github.com/hkuhn42/openhab2.roomba) and will heavily use
Roomba980-Python project by Nick Waterton (http://github.com/NickWaterton/Roomba980-Python) as a reference. The goal is to
implement a binding that interfaces directly to the robot without a need for a dedicated MQTT server.