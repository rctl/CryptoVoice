## RTVOICE - Real time vocie communication

RTVOICE is an app offering real time voice communication over networking. Implemented together with a cutom dialing protocol.
Server backend is written in java. App is also available in the Google Play Store.

**License**

This project is licensed under GNU AGPLv3. If you use this code you have to release your source code publicly. 

### Setup

* Setup a server running the Switchboard found in server/ folder
* Make sure server is accessable and no ports are being blocked
* Go into /app/src/main/java/io/rtek/rtvoice/ and move Settings.java.example to Settings.java
* Change settings in Settings.java to reflect your server ip address
* Compile and run app

### App screens

![Main screen](https://github.com/rctl/rtvoice/raw/master/docs/main.png)

Main screen

![Incoming call](https://github.com/rctl/rtvoice/raw/master/docs/incoming.png)

Incoming call

![Call connecting](https://github.com/rctl/rtvoice/raw/master/docs/call.png)

Call connecting
