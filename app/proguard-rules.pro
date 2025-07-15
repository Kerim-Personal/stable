# Bu dosya, getDefaultProguardFile('proguard-android-optimize.txt') tarafından
# sağlanan varsayılan kurallara ek olarak kullanılır.

#------------- Kotlin Coroutines Kuralları -------------
# Coroutines'in dahili hata ayıklama mekanizmalarının çalışmasını sağlar.
-keep class kotlinx.coroutines.debug.internal.DebugProbesKt {
    <fields>;
    <methods>;
}

#------------- Hilt Kuralları -------------
# Hilt tarafından oluşturulan ve kullanılan sınıfların korunmasını sağlar.
# Hilt'in Gradle eklentisi genellikle bu kuralları otomatik olarak ekler,
# ancak manuel olarak eklemek güvenliği artırır.
-keep class * implements dagger.hilt.internal.GeneratedComponent {
    <methods>;
}
-keep class * implements dagger.hilt.internal.GeneratedEntryPoint {
    <methods>;
}
-keep class * implements dagger.hilt.internal.GeneratedComponentManager {
    <methods>;
}
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder {
    <methods>;
}
-keep class dagger.hilt.internal.processedroots.* {
    <methods>;
}
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
# @AndroidEntryPoint, @HiltViewModel, @HiltAndroidApp ile işaretlenmiş sınıfları koru.
-keepclassmembers class ** {
    @dagger.hilt.android.AndroidEntryPoint <fields>;
    @dagger.hilt.android.HiltAndroidApp <fields>;
    @dagger.hilt.android.lifecycle.HiltViewModel <fields>;
}
-keep class * {
    @dagger.hilt.android.AndroidEntryPoint *;
    @dagger.hilt.android.HiltAndroidApp *;
    @dagger.hilt.android.lifecycle.HiltViewModel *;
}

#------------- Room Kuralları -------------
# Room veritabanı varlık (entity) sınıflarını korur.
# Bu sınıfların alan adları veritabanı sütun adlarıyla eşleştiği için
# yeniden adlandırılmamalıdır.
-keep class androidx.room.** { *; }
-keepclassmembers class * {
    @androidx.room.Entity *;
}
-keepclassmembers class * {
    @androidx.room.Dao *;
}
-keepclassmembers class * {
    @androidx.room.Database *;
}
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}
-keepclassmembers class * {
    @androidx.room.PrimaryKey *;
}
-keepclassmembers class * {
    @androidx.room.Embedded *;
}
-keepclassmembers class * {
    @androidx.room.Relation *;
}

#------------- Veri/Model Sınıfları (Data/Model Classes) -------------
# API yanıtları veya veritabanı modelleri gibi serileştirme/çözme işlemi
# gören veri sınıflarını korur. Paket adını kendi projenize göre güncelleyin.
-keep class com.codenzi.snapnote.data.model.** { *; }
-keepclassmembers class com.codenzi.snapnote.data.model.** {
    <fields>;
    <methods>;
}

#------------- Genel Kurallar -------------
# Parcelable arayüzünü uygulayan sınıfları korur.
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Enum sınıflarının `valueOf` metodunu korur.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}