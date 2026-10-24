# Workflows are stored and shared as JSON via kotlinx.serialization.
# The library ships its own rules; keep our serializable model explicitly too.
-keep,includedescriptorclasses class com.retweakeverything.osmoremote.core.workflow.**$$serializer { *; }
-keepclassmembers class com.retweakeverything.osmoremote.core.workflow.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
