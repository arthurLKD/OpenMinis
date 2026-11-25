# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# OpenUI JavascriptInterface and bridge protection
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.openminis.app.ui.markdown.OpenUIViewKt** { *; }


# [T-android-vdisplay] Shizuku instantiates the virtual-display UserService
# entry point BY NAME via reflection inside the privileged UserService
# process, and the AIDL Stub dispatches transactions to its methods. R8 must
# not rename or shrink the class or its two constructors, otherwise Shizuku
# fails with ClassNotFoundException / NoSuchMethodException at bind time —
# a release-only failure (debug builds don't minify).
-keep class com.openminis.app.vdisplay.VDisplayUserService {
    <init>(android.content.Context);
    <init>();
}
# The AIDL binder interface is passed across processes; keep the Stub/Proxy
# plumbing and the interface itself so cross-process transactions resolve.
-keep class com.openminis.app.vdisplay.IVirtualDisplayService { *; }
-keep class com.openminis.app.vdisplay.IVirtualDisplayService$* { *; }
