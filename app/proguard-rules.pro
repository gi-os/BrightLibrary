# Keep rules for LightBooks, under R8 *full mode* (see gradle.properties).
#
# Every rule below names the mechanism that makes it necessary. There is deliberately no
# blanket `-keep class com.lightfastread.**`: that keeps the whole app and turns minification
# into a no-op with extra build time. If a full-mode build breaks, the first thing to suspect
# is that a `-keep` on a class no longer implies keeping its members.

# ------------------------------------------------------------------ crash reports

# Shake-to-report attaches the last crash's stack trace to a GitHub issue, and a trace of
# `a.a.a(Unknown Source)` is not a bug report. Costs a few KB in the APK and is the difference
# between a report that can be triaged and one that cannot.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------ kotlinx.serialization

# The shelf and the settings are stored as JSON keyed by property name. The compiler plugin
# generates a `$serializer` for each `@Serializable` class and reaches it through the
# companion, which R8 cannot see as a call — kotlinx ships consumer rules for this, repeated
# here because full mode is stricter about members than the version those rules were written
# against.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.lightfastread.** {
    *** Companion;
}
-keepclasseswithmembers class com.lightfastread.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.lightfastread.**
-keepclassmembers class com.lightfastread.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.lightfastread.**
-keep,includedescriptorclasses class com.lightfastread.<1>$$serializer {
    *** INSTANCE;
    <fields>;
}

# The enums stored inside those objects — theme mode, swipe mode, bionic mode, title style —
# are serialised by *constant name*, not by ordinal, so a renamed constant silently reads back
# as the default and the user's settings reset. Only the names have to survive; the classes
# themselves may be renamed freely.
-keepclassmembers enum com.lightfastread.data.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------ book parsers

# Nothing to keep for the EPUB and MOBI readers, and that is worth writing down rather than
# rediscovering. Both are byte- and regex-based: the EPUB side unzips entries and pulls
# `container.xml`, the OPF, the NCX and the nav document apart with regexes, and the MOBI side
# walks EXTH records by offset. No SAX handler, no `XmlPullParserFactory`, no parser named as a
# string anywhere — so full mode has nothing to remove behind the app's back here.

# ------------------------------------------------------------------ LightSync

# The backup provider is named as a string in the manifest and is kept by light-common's own
# consumer rules, which also keep its no-arg constructor explicitly. Nothing needed here.

# ------------------------------------------------------------------ Room

# None. This app has no database; the shelf is JSON in SharedPreferences.
