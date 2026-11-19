# play-services-ads pulls Room 2.2.5 (via work-runtime), whose consumer rules do not keep the
# RoomDatabase constructor under R8 full mode; without this the app crashes at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
