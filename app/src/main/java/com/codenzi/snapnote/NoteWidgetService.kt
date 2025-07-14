package com.codenzi.snapnote

import android.content.Intent
import android.widget.RemoteViewsService

class NoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return NoteWidgetItemFactory(this.applicationContext)
    }
}