# R8 / ProGuard rules for Stride release builds.
#
# WHY THIS FILE EXISTS
# --------------------
# The first real release build of Stride crashed on launch:
#
#   Unable to get provider androidx.startup.InitializationProvider:
#   Failed to create an instance of class androidx.work.impl.WorkDatabase
#
# Room does not link its database implementation at compile time. It builds the name
# "<YourDatabase>_Impl" at runtime, loads it with Class.forName, and calls its no-arg
# constructor. R8 sees a class that nothing references and a constructor nobody calls,
# concludes both are dead, and removes them. Debug builds do not minify, so this is
# invisible until the moment you ship - which for Stride is the moment it becomes the
# launcher on a treadmill and the console has no home screen left.
#
# WorkManager is what pulls Room in: StrideAppstoreService's periodic update check is a
# WorkManager job, and WorkManager keeps its queue in a Room database that is created
# eagerly by androidx.startup at process start. So the crash happens before any Stride
# code runs, on every launch, not just when an update check is due.

# Keep every Room database's generated implementation and the constructor Room calls.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { public <init>(); }

# Room's generated *_Impl classes are referenced only by name.
-keep class **_Impl { *; }

# WorkManager instantiates Workers by class name from the persisted queue, so a Worker
# that is only ever scheduled (never constructed in code) looks dead to R8 too.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.work.Worker { <init>(...); }

# androidx.startup initializers are named in the manifest, not called from code.
-keep class * implements androidx.startup.Initializer { <init>(); }

# Room emits references to optional paging/rxjava integrations we do not use.
-dontwarn androidx.room.paging.**
