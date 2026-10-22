# Installing Home on your Fire TV

Step-by-step guide for the Fire TV 4-Series (`AMZ_4k55N400U`, Fire OS 7.7) from a Mac. It takes about 15 minutes the first time.

You'll need:
- [Android Studio](https://developer.android.com/studio) installed (it provides `adb` and the Android SDK).
- A JDK between 17 and 21 for building (Android Studio's own bundled Java may be newer than Gradle 8.11 supports).
- The TV and the Mac on the **same home network** (Wi-Fi or wired, as long as it's the same router).
- The TV remote.
- The Terminal app on the Mac (press ⌘ Space, type `Terminal`, press Return).

---

## Part 1: Build the app (on the Mac)

If `app/build/outputs/apk/release/app-release.apk` already exists and you haven't changed any code, skip to Part 2.

1. Open Terminal and go to the project folder (wherever you cloned or downloaded it):
   ```sh
   cd path/to/Amazon-TV-Simple-Launcher
   ```
2. Build the release version (the fast, optimised one):
   ```sh
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # or any JDK 17–21
   ./gradlew assembleRelease
   ```
   Wait for `BUILD SUCCESSFUL`. The app is now at `app/build/outputs/apk/release/app-release.apk`.

   > If the build fails with just a Java version number as the error (e.g. `25.0.3`), Gradle is using a Java that's too new: point `JAVA_HOME` at a JDK 17–21.

---

## Part 2: Prepare the TV

### Step 1: Turn on Developer options

Developer options are hidden until you unlock them.

1. On the TV, press **Home**, then go to **Settings** (the gear icon at the right end of the top menu).
2. Open **Device & Software** (called **My Fire TV** on some models).
3. Open **About**.
4. Highlight the first line (the TV's name, e.g. *Fire TV 4-Series*) and press **OK (the centre button) 7 times**.
5. A message says **"No need, you are already a developer"** or **"You are now a developer"**.
6. Press **Back**. **Developer options** now appears in the **Device & Software** menu.

### Step 2: Turn on ADB debugging

1. **Settings → Device & Software → Developer options**.
2. Turn **ADB debugging** **ON**. Confirm the warning if one appears.
3. Leave **Apps from Unknown Sources** as it is: installing from a computer doesn't need it.

### Step 3: Find the TV's IP address

1. **Settings → Device & Software → About → Network**.
2. Write down the **IP Address**, e.g. `192.168.1.57`.

   > The IP can change if the router restarts. If a later step can't connect, check it again here. You can fix it in your router settings ("DHCP reservation") so it never changes.

---

## Part 3: Connect the Mac to the TV

1. **Close the Android emulator** if it's open. With two devices attached, `adb` doesn't know which one you mean.
2. In Terminal, make `adb` available for this Terminal window:
   ```sh
   export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
   ```
   (Run this again any time you open a new Terminal window.)
3. Connect, using **your** TV's IP address:
   ```sh
   adb connect 192.168.1.57:5555
   ```
4. **Look at the TV.** A box appears: **"Allow USB debugging?"**
   - Highlight **"Always allow from this computer"** and press OK to tick it.
   - Then highlight **OK** and press it.
5. Check the connection:
   ```sh
   adb devices
   ```
   You should see a line like:
   ```
   192.168.1.57:5555   device
   ```
   - `unauthorized`: you missed or declined the prompt on the TV. Run `adb disconnect`, then repeat step 3 and accept it.
   - `offline` or nothing listed: see **Troubleshooting** below.
6. Optional: confirm it's the right TV and software:
   ```sh
   adb shell getprop ro.product.model
   adb shell getprop ro.build.version.name
   ```
   The first prints the TV's model code (it starts with `AFT`), and the second the Fire OS version (it should start with `7.7`).

---

## Part 4: Install the app and set up the Home button

Fire OS doesn't let you change the default home screen, so the app includes a small background helper that spots the Home button and opens your screen instead. Setting it up needs a few commands, so `install-on-tv.sh` does it all:

- installs the app
- lets the helper read the system log, which is how it sees every Home press
- removes a 5-second delay Android normally puts on opening apps right after Home
- switches the helper on, keeping any accessibility services you already use (e.g. VoiceView)

1. From the project folder (see Part 1, step 1), using **your** TV's IP address:
   ```sh
   ./install-on-tv.sh 192.168.1.57
   ```
2. It ends with one of:
   - **`Done. Press Home on the remote to try it.`** All set.
   - **`Restart the TV to finish`**: run the command it prints (`adb ... reboot`) and wait for the TV to come back.
   - **`Can't talk to the TV yet`**: accept the "Allow USB debugging?" prompt on the TV (Part 3, step 4) and run it again.
3. **Test it:** open any app (e.g. YouTube) and press **Home**. Your screen appears straight away.
4. **Test the escape route:** press **Home twice quickly**. You stay on Amazon's home screen. Press Home once more to come back to yours.
5. **Long-press Home** still opens Fire TV's quick menu (sleep, settings, etc.).

The app also appears in Amazon's app list as **Home**.

### If pressing Home doesn't switch over

Run the script again first: it's safe to repeat. If that doesn't fix it, run this and press Home a few times on the TV:
```sh
adb logcat -s HomeCatch
```
Press `Ctrl+C` to stop it, and send me what it printed.

---

## Part 6: Check it survives a restart

1. Restart the TV:
   ```sh
   adb reboot
   ```
2. When it comes back, the new home screen opens by itself shortly after Amazon's appears.
3. If it doesn't, check the helper is still on:
   ```sh
   adb connect 192.168.1.57:5555
   adb shell settings get secure enabled_accessibility_services
   ```
   If `xyz.bodyparts.home` is missing, run `./install-on-tv.sh` again.

---

## Part 7: Tidy up (optional)

- **Choose which apps appear:** press **Menu (☰)** → **Choose apps to show**, tick the ones you want, and press **Done**.
- **Reorder apps:** select an app, press **Menu (☰)** → **Move**. The tile wiggles: use ◀ ▶ to slide it along the row, then press **OK**. Or Menu (☰) on any non-app tile → **Reorder apps…** and pick from the list.
- **The cat:** Menu (☰) on a non-app tile → **Cat: On/Off**.
- **ADB debugging** can be turned off again (Settings → Device & Software → Developer options). The home screen keeps working without it. Turn it back on when you want to install an update.

---

## Updating to a new version later

1. Rebuild (Part 1).
2. Turn ADB debugging on if you turned it off.
3. Run the script again:
   ```sh
   ./install-on-tv.sh 192.168.1.57
   ```
   Your app order, hidden apps and settings are kept.

## Going back to Amazon's home screen

- **For a moment:** press Home twice quickly, or select the **Fire TV** tile.
- **Turn off the Home-button switch:** Menu (☰) → **Home button opens this screen: Off**.
- **Remove it completely:**
  ```sh
  adb uninstall xyz.bodyparts.home
  ```

## After a Fire OS update

Amazon's system updates can switch the helper off or reset the settings it needs. If pressing Home stops opening your screen after an update, turn ADB debugging back on if needed and run `./install-on-tv.sh` again.

---

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `adb: command not found` | Run the `export PATH=...` line from Part 3, step 2. |
| `failed to connect` / `Connection refused` | Check the TV and Mac are on the same network, ADB debugging is ON, and the IP is right (Part 2, step 3). Restarting the TV's ADB can help: turn ADB debugging off and on again. |
| `unauthorized` in `adb devices` | `adb disconnect`, then `adb connect <IP>:5555` and accept the prompt on the TV. Still stuck: `adb kill-server`, then connect again. |
| `more than one device/emulator` | Close the Android emulator, or add `-s <IP>:5555` after `adb` in each command, e.g. `adb -s 192.168.1.57:5555 install -r ...`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was built on a different computer. Run `adb uninstall xyz.bodyparts.home`, then install again (this resets your app order and hidden apps). |
| An HDMI input tile shows "Couldn't open" or no picture | Your TV may name or switch inputs differently. Please [open an issue](https://github.com/tomtomwillis/Amazon-TV-Simple-Launcher/issues) with your TV model and the output of `adb shell dumpsys tv_input`. |
| Home button stopped switching | Run `./install-on-tv.sh` again. If it still doesn't work, restart the TV (`adb reboot`). |
| Home shows Amazon's screen for ~5 seconds before yours | The delay setting was reset. Run `./install-on-tv.sh` again. |
| The app row is empty | Press Menu (☰) → **Choose apps to show** and make sure some apps are ticked. |
