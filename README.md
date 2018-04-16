
## CryptoVoice- Real time encrypted vocie communication

CryptoVoice is an app offering real time encrypted voice communication over networking. Implemented together with a cutom dialing and voice streaming protocol encrypted with strong AES cipher. Dialing channel uses TLS encryption with server validation to prevent MITM attacks.
Server backend is written in java. App is also available in the Google Play Store.

For more details please read [my blog post on the project.](http://blog.rasmusj.se/2017/02/cryptovoice-encrypted-voice-calls.html)

**License**

This project is licensed under GNU AGPLv3. If you use this code you have to release your source code publicly. 

`This project is no longer maintained. Feel free to reach out if you need help adapting the code for your setting.`

### Setup

**Follow these steps to setup a backend server (required for app to work) and to configure the android client to use your backend server**

* Go into the server folder of the repository.
* Generate server certificate with `keytool -genkey -keystore keystore.jks -keyalg RSA` (Save password used here)
* Self sign server certificate with `keytool -selfcert -alias mykey -keystore keystore.jks -validity 3950`
* Export certificate as crt file with `keytool -export -alias mykey -keystore keystore.jks -rfc -file server.crt`
* Move certificate file into android asset folder with `mv server.crt ../app/main/assets/`
* Move the Settings.java.example file in server folder to Settings.java and edit the file with your keystore password used in earlier steps
* Compile and run the server (prefferably at a publicly accessiable server) `javac *.java && java Switchboard`
* Make sure server is accessable and no ports are being blocked
* Go into *app/src/main/java/io/rtek/rtvoice/* and move **Settings.java.example** to **Settings.java**
* **Change settings in Settings.java to reflect your server ip address**
* Compile and run app

### App screens

![Main screen](https://github.com/rctl/rtvoice/raw/master/docs/main.png)

Main screen

![Incoming call](https://github.com/rctl/rtvoice/raw/master/docs/incoming.png)

Incoming call

![Call connecting](https://github.com/rctl/rtvoice/raw/master/docs/call.png)

Call connecting
