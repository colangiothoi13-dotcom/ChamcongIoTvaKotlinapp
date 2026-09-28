# Firestore maps Kotlin model fields by reflection. Keep the model package in
# release builds so attendance/device documents continue to deserialize.
-keep class vn.chamcong.iot.model.** { *; }

# Firebase/Play services use reflective serializers and task callbacks.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
