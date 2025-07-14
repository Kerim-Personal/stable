// kerim-personal/acilnot/AcilNot-67f040771546d8d1c2779533e2d914d9dbbd06cc/app/src/main/java/com/codenzi/snapnote/NoteContent.kt

package com.codenzi.snapnote

// Base64 alanları kaldırıldı.
data class NoteContent(
    var text: String,
    var checklist: MutableList<ChecklistItem>,
    val audioFilePath: String? = null,
    val imagePath: String? = null
)