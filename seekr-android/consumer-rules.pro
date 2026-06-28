# kotlinx-serialization keeps the generated serializers for the SDK's internal DTOs.
# They live in tv.seekr.previews.core.internal and are referenced reflectively at
# (de)serialization time, so keep them and their synthetic Companion serializers.
-keepclassmembers class tv.seekr.previews.core.internal.** {
    *** Companion;
}
-keepclasseswithmembers class tv.seekr.previews.core.internal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
