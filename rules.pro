# R8 rules for the plugin payload (build.sh).

# The entry class droidtop loads by name (manifest entryClass).
-keep class dev.droidtop.plugins.githubapps.GithubAppsPlugin { public <init>(); public *; }

-allowaccessmodification
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontnote **

# Kotlin's @NotNull/@Nullable annotations are not on the classpath; they are only markers.
-dontwarn org.jetbrains.annotations.**
