# Gson tarafından kullanılan veri sınıflarının (data class) alanlarının
# isimlerinin değiştirilmesini ve silinmesini engelle.

# Not içeriği ve kontrol listesi için
-keep class com.codenzi.snapnote.NoteContent { *; }
-keep class com.codenzi.snapnote.ChecklistItem { *; }

# Google Drive yedekleme ve geri yükleme işlemleri için
-keep class com.codenzi.snapnote.BackupData { *; }
-keep class com.codenzi.snapnote.AppSettings { *; }

# Google Drive API'sinin kullandığı veri sınıfları için
-keep class com.codenzi.snapnote.DriveFile { *; }
-keep class com.codenzi.snapnote.DriveFileList { *; }

# Ayrıca, Hilt ve Coroutines için genel kurallar
-dontwarn kotlinx.coroutines.flow.**
