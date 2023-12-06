package eu.kanade.tachiyomi.data.backup

internal object BackupCreateFlags {
    const val BACKUP_CATEGORY = 0x1
    const val BACKUP_CHAPTER = 0x2
    const val BACKUP_HISTORY = 0x4
    const val BACKUP_TRACK = 0x8
    const val BACKUP_PREFS = 0x10
    const val BACKUP_EXT_PREFS = 0x20
    const val BACKUP_EXTENSIONS = 0x40

    // AM (CU) -->
    internal const val BACKUP_CUSTOM_INFO = 0x80
    // <-- AM (CU)

    const val AutomaticDefaults = BACKUP_CATEGORY or
        BACKUP_CHAPTER or
        BACKUP_HISTORY or
        BACKUP_TRACK or
        BACKUP_PREFS or
        BACKUP_EXT_PREFS
}
