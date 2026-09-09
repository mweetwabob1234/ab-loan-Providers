# A&B Loan Book — Android app

The dashboard from the Albert and Bob loan register, wrapped as an Android
app. The interface is exactly the page you already have: it is bundled at
`app/src/main/assets/index.html` and rendered full screen in a WebView.

## Building the APK

You need the Android SDK to produce an APK. Three ways to get one, easiest
first.

### 1. GitHub Actions — no software to install

1. Create a new repository on GitHub (private is fine).
2. Upload this whole folder to it.
3. Open the **Actions** tab. The "Build APK" workflow runs on its own.
4. When it finishes, open the run and download the **ABLoanBook-apk**
   artifact. Inside is `app-release.apk`.

Everything happens on GitHub's machines and costs nothing on a normal
free account.

### 2. Android Studio

1. Install Android Studio and open this folder.
2. Let it sync — it downloads the SDK and Gradle plugin itself.
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
4. The APK lands in `app/build/outputs/apk/release/`.

### 3. Command line, with the SDK already installed

```
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease
```

## Installing it on a phone

The APK is signed with the standard debug key, so it installs by
sideloading without any keystore setup:

1. Copy the APK to the phone (cable, WhatsApp to yourself, Drive).
2. Tap it. Android will ask permission to install from that source —
   allow it for whichever app you opened the file from.
3. It appears in the launcher as **A&B Loan Book**.

A debug-signed APK cannot be uploaded to the Play Store. If you ever
want it on the Store, generate a release keystore and point
`signingConfig` in `app/build.gradle` at it.

## Updating the figures

Open `app/src/main/assets/index.html` and edit the `LOANS` array near the
bottom. Every number on the screen — outstanding balance, interest,
cash position, the per-borrower rows — is calculated from those rows.
Rebuild and reinstall to publish the change.

## What is in here

```
app/src/main/assets/index.html      the dashboard interface
app/src/main/java/.../MainActivity.java   WebView shell
app/src/main/AndroidManifest.xml    permissions, launcher entry
app/src/main/res/                   icons, colours, app name
.github/workflows/build-apk.yml     the free cloud build
```

## Note on the data

The register in the spreadsheet holds NRC numbers, phone numbers and next
of kin details. None of that is in this app — it carries only the loan
figures and borrower names needed for the dashboard.
