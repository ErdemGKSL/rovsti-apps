# WaPush.token() reads the FCM token by reflection so Play Services stay off the compile classpath (AD-WA6). Keep the
# names it looks up; in an app without Firebase (the `plain` flavor) these rules match nothing.
-keep class com.google.firebase.messaging.FirebaseMessaging {
    public static com.google.firebase.messaging.FirebaseMessaging getInstance();
    public com.google.android.gms.tasks.Task getToken();
}
-keepclassmembers class * extends com.google.android.gms.tasks.Task {
    public boolean isComplete();
    public boolean isSuccessful();
    public java.lang.Object getResult();
}
