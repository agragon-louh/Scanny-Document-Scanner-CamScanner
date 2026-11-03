package com.scanny.scanner.db;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.widget.Toast;

import com.scanny.scanner.main_utils.Constant;
import com.scanny.scanner.models.DBModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Every group (folder) is a row in {@code alldocs}, and its pages live in a table of their own.
 *
 * <p>Since version 2 that table has a generated name ({@code "group <id>"}, stored in
 * {@code alldocs.tbl}) and is looked up from the folder name. Version 1 derived the table name
 * from the folder name with spaces removed, and SQLite compares table names case-insensitively,
 * so folders such as "Doc"/"doc" or "My Doc"/"MyDoc" shared one table: deleting, renaming or
 * moving one of them destroyed the other folder's pages.
 *
 * <p>The public methods still take the folder name. Folder names are kept unique
 * (case-insensitively) so that a name identifies exactly one folder.
 */
public class DBHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "DocumentDB";
    private static final int DATABASE_VERSION = 3;
    private static final String KEY_FIRST_IMAGE = "firstimage";
    private static final String KEY_ID = "id";
    private static final String KEY_IMG_NAME = "imgname";
    private static final String KEY_IMG_NOTE = "imgnote";
    private static final String KEY_IMG_PATH = "imgpath";
    private static final String KEY_TABLE_DATE = "date";
    private static final String KEY_TABLE_NAME = "name";
    private static final String KEY_TAG = "tag";
    private static final String KEY_DOC_TABLE = "tbl";
    private static final String TABLE_NAME = "alldocs";
    private static final String DOC_TABLE_COLUMNS = "(" + KEY_ID + " INTEGER PRIMARY KEY," + KEY_IMG_NAME + " TEXT," + KEY_IMG_NOTE + " TEXT," + KEY_IMG_PATH + " TEXT)";
    private static final String TAG = "DBHelper";

    /** A folder's alldocs row id and the table that holds its pages. */
    private static final class GroupRef {
        final long id;
        final String table;

        GroupRef(long id, String table) {
            this.id = id;
            this.table = table;
        }
    }

    /**
     * True for tables that must never be used as (or dropped as) a folder's page table: the folder
     * list itself and SQLite/Android bookkeeping tables. Compared case-insensitively, like SQLite.
     */
    private static boolean isReservedTable(String table) {
        String lower = table.toLowerCase(java.util.Locale.ROOT);
        return lower.equals(TABLE_NAME) || lower.equals("android_metadata") || lower.startsWith("sqlite_");
    }

    /** Identifiers cannot be bound as query parameters, so they are quoted and escaped. */
    private static String quote(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }

    /**
     * Returns true when {@code name} can be used as a group/folder name. Names become part of
     * exported PDF file names, so path separators and control characters are rejected.
     */
    public static boolean isValidGroupName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            return false;
        }
        if (name.contains("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '\'') {
                return false;
            }
        }
        return true;
    }

    /** Page images live under the app's external files dir; only files in there are deleted. */
    private final File imageRoot;

    public DBHelper(Context context) {
        super(context, DATABASE_NAME, (SQLiteDatabase.CursorFactory) null, DATABASE_VERSION);
        imageRoot = context.getExternalFilesDir(null);
    }

    @Override
    public void onCreate(SQLiteDatabase sQLiteDatabase) {
        sQLiteDatabase.execSQL("CREATE TABLE alldocs(id INTEGER PRIMARY KEY,name TEXT,date TEXT,tag TEXT,firstimage TEXT,tbl TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase sQLiteDatabase, int oldVersion, int newVersion) {
        // Runs inside a transaction opened by SQLiteOpenHelper. Never drop user data here.
        if (oldVersion < 2) {
            sQLiteDatabase.execSQL("ALTER TABLE alldocs ADD COLUMN tbl TEXT");
            Cursor cursor = sQLiteDatabase.rawQuery("SELECT id, name FROM alldocs", null);
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String name = cursor.getString(1);
                    // Keep pointing at the version 1 table. Folders that already shared a table
                    // keep sharing it: their pages cannot be told apart any more.
                    String table = name == null ? "" : name.replace(" ", "");
                    // A folder named e.g. "All Docs" would otherwise point at the folder list itself.
                    if (table.isEmpty() || isReservedTable(table)) {
                        table = "group " + id;
                    }
                    sQLiteDatabase.execSQL("CREATE TABLE IF NOT EXISTS " + quote(table) + DOC_TABLE_COLUMNS);
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(KEY_DOC_TABLE, table);
                    sQLiteDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(id)});
                }
            } finally {
                cursor.close();
            }
        }
        if (oldVersion < 3) {
            // Databases upgraded by the first version 2 release may already point a folder at a
            // reserved table. Give such folders a table of their own.
            Cursor cursor = sQLiteDatabase.rawQuery("SELECT id, tbl FROM alldocs", null);
            try {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String table = cursor.getString(1);
                    if (table == null || isReservedTable(table)) {
                        String newTable = "group " + id;
                        sQLiteDatabase.execSQL("CREATE TABLE IF NOT EXISTS " + quote(newTable) + DOC_TABLE_COLUMNS);
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(KEY_DOC_TABLE, newTable);
                        sQLiteDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(id)});
                    }
                }
            } finally {
                cursor.close();
            }
        }
    }

    /** Looks a folder up by name: an exact match first, otherwise a case-insensitive one. */
    private static GroupRef findGroup(SQLiteDatabase db, String groupName) {
        if (groupName == null) {
            return null;
        }
        Cursor cursor = db.rawQuery("SELECT id, tbl FROM alldocs WHERE name = ? COLLATE NOCASE ORDER BY name = ? DESC LIMIT 1", new String[]{groupName, groupName});
        try {
            if (!cursor.moveToFirst() || cursor.isNull(1) || isReservedTable(cursor.getString(1))) {
                Log.w(TAG, "No table for group");
                return null;
            }
            return new GroupRef(cursor.getLong(0), cursor.getString(1));
        } finally {
            cursor.close();
        }
    }

    private static boolean isGroupNameTaken(SQLiteDatabase db, String name, String ignoredName) {
        Cursor cursor = ignoredName == null
                ? db.rawQuery("SELECT 1 FROM alldocs WHERE name = ? COLLATE NOCASE LIMIT 1", new String[]{name})
                : db.rawQuery("SELECT 1 FROM alldocs WHERE name = ? COLLATE NOCASE AND name <> ? LIMIT 1", new String[]{name, ignoredName});
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    /** True when another folder already uses this name (ignoring case). */
    public boolean isGroupNameTaken(String name) {
        return isGroupNameTaken(getReadableDatabase(), name, null);
    }

    /**
     * Creates a folder and its page table in one transaction. If the name is already taken a
     * suffix is added ("Scanny_0311_2"), so callers must use the returned name.
     */
    public String createGroup(DBModel dBModel) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        writableDatabase.beginTransaction();
        try {
            String baseName = dBModel.getGroup_name();
            String name = baseName;
            for (int i = 2; isGroupNameTaken(writableDatabase, name, null); i++) {
                name = baseName + "_" + i;
            }
            ContentValues contentValues = new ContentValues();
            contentValues.put("name", name);
            contentValues.put("date", dBModel.getGroup_date());
            contentValues.put(KEY_TAG, dBModel.getGroup_tag());
            contentValues.put(KEY_FIRST_IMAGE, dBModel.getGroup_first_img());
            long id = writableDatabase.insertOrThrow(TABLE_NAME, null, contentValues);
            // Version 1 table names never contain a space, so this cannot collide with them.
            String table = "group " + id;
            // Row ids can be reused after a delete; never inherit pages from an old table.
            writableDatabase.execSQL("DROP TABLE IF EXISTS " + quote(table));
            writableDatabase.execSQL("CREATE TABLE " + quote(table) + DOC_TABLE_COLUMNS);
            ContentValues tableValues = new ContentValues();
            tableValues.put(KEY_DOC_TABLE, table);
            writableDatabase.update(TABLE_NAME, tableValues, "id = ?", new String[]{String.valueOf(id)});
            writableDatabase.setTransactionSuccessful();
            dBModel.setGroup_name(name);
            return name;
        } finally {
            writableDatabase.endTransaction();
            writableDatabase.close();
        }
    }

    /**
     * Adds a page to a folder. The image file is already on disk at this point, so if the folder
     * cannot be found (e.g. it was renamed while a screen still held the old name, or the name was
     * lost when the process was killed) a folder is created for the page instead of dropping it.
     */
    public void addGroupDoc(String name, String path, String img_name, String note) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        GroupRef group = findGroup(writableDatabase, name);
        if (group == null) {
            Log.w(TAG, "Folder not found; creating one so the page is not lost");
            String groupName = isValidGroupName(name) ? name : "Scanny" + Constant.getDateTime("_ddMMHHmmss");
            groupName = createGroup(new DBModel(groupName, Constant.getDateTime("yyyy-MM-dd  hh:mm a"), path, Constant.current_tag));
            writableDatabase = getWritableDatabase();
            group = findGroup(writableDatabase, groupName);
        }
        if (group != null) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(KEY_IMG_PATH, path);
            contentValues.put(KEY_IMG_NAME, img_name);
            contentValues.put(KEY_IMG_NOTE, note);
            writableDatabase.insert(quote(group.table), null, contentValues);
        }
        writableDatabase.close();
    }

    /**
     * Moves every page (with its note) of folder {@code from} into folder {@code to} and deletes
     * {@code from}, all in one transaction. Returns false and changes nothing on failure.
     */
    public boolean moveGroup(String from, String to) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        writableDatabase.beginTransaction();
        try {
            GroupRef source = findGroup(writableDatabase, from);
            GroupRef target = findGroup(writableDatabase, to);
            if (source == null || target == null || source.table.equalsIgnoreCase(target.table)) {
                return false;
            }
            writableDatabase.execSQL("INSERT INTO " + quote(target.table) + "(" + KEY_IMG_NAME + "," + KEY_IMG_NOTE + "," + KEY_IMG_PATH + ") SELECT "
                    + KEY_IMG_NAME + "," + KEY_IMG_NOTE + "," + KEY_IMG_PATH + " FROM " + quote(source.table));
            // An empty target has no cover image; it would keep showing as an empty folder.
            writableDatabase.execSQL("UPDATE alldocs SET firstimage = (SELECT firstimage FROM alldocs WHERE id = ?) WHERE id = ? AND (firstimage IS NULL OR firstimage = '')",
                    new Object[]{source.id, target.id});
            deleteGroup(writableDatabase, source);
            writableDatabase.setTransactionSuccessful();
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "moveGroup failed", e);
            return false;
        } finally {
            writableDatabase.endTransaction();
            writableDatabase.close();
        }
    }

    public ArrayList<DBModel> getAllGroups() {
        ArrayList<DBModel> arrayList = new ArrayList<>();
        Cursor rawQuery = getReadableDatabase().rawQuery("SELECT  * FROM alldocs", (String[]) null);
        if (rawQuery.moveToFirst()) {
            do {
                DBModel dBModel = new DBModel();
                dBModel.setId(Integer.parseInt(rawQuery.getString(0)));
                dBModel.setGroup_name(rawQuery.getString(1));
                dBModel.setGroup_date(rawQuery.getString(2));
                dBModel.setGroup_tag(rawQuery.getString(3));
                dBModel.setGroup_first_img(rawQuery.getString(4));
                arrayList.add(dBModel);
            } while (rawQuery.moveToNext());
        }
        rawQuery.close();
        return arrayList;
    }

    public ArrayList<DBModel> getOnlyAllGroups() {
        ArrayList<DBModel> arrayList = new ArrayList<>();
        Cursor rawQuery = getReadableDatabase().rawQuery("SELECT  * FROM alldocs", (String[]) null);
        if (rawQuery.moveToFirst()) {
            do {
                DBModel dBModel = new DBModel();
                dBModel.setId(Integer.parseInt(rawQuery.getString(0)));
                dBModel.setGroup_name(rawQuery.getString(1));
                dBModel.setGroup_date(rawQuery.getString(2));
                dBModel.setGroup_tag(rawQuery.getString(3));
                dBModel.setGroup_first_img(rawQuery.getString(4));
                if (dBModel.getGroup_first_img() == null || dBModel.getGroup_first_img().isEmpty()) arrayList.add(dBModel);
            } while (rawQuery.moveToNext());
        }
        rawQuery.close();
        return arrayList;
    }

    public ArrayList<DBModel> getGroupsByTag(String str) {
        ArrayList<DBModel> arrayList = new ArrayList<>();
        Cursor rawQuery = getReadableDatabase().rawQuery("SELECT  * FROM alldocs WHERE tag = ?", new String[]{str});
        if (rawQuery.moveToFirst()) {
            do {
                DBModel dBModel = new DBModel();
                dBModel.setId(Integer.parseInt(rawQuery.getString(0)));
                dBModel.setGroup_name(rawQuery.getString(1));
                dBModel.setGroup_date(rawQuery.getString(2));
                dBModel.setGroup_tag(rawQuery.getString(3));
                dBModel.setGroup_first_img(rawQuery.getString(4));
                arrayList.add(dBModel);
            } while (rawQuery.moveToNext());
        }
        rawQuery.close();
        return arrayList;
    }

    public ArrayList<DBModel> getGroupDocs(String str) {
        ArrayList<DBModel> arrayList = new ArrayList<>();
        SQLiteDatabase readableDatabase = getReadableDatabase();
        GroupRef group = findGroup(readableDatabase, str);
        if (group == null) {
            return arrayList;
        }
        Cursor rawQuery = readableDatabase.rawQuery("SELECT  * FROM " + quote(group.table), (String[]) null);
        if (rawQuery.moveToFirst()) {
            do {
                DBModel dBModel = new DBModel();
                dBModel.setId(Integer.parseInt(rawQuery.getString(0)));
                dBModel.setGroup_doc_name(rawQuery.getString(1));
                dBModel.setGroup_doc_note(rawQuery.getString(2));
                dBModel.setGroup_doc_img(rawQuery.getString(3));
                arrayList.add(dBModel);
            } while (rawQuery.moveToNext());
        }
        rawQuery.close();
        return arrayList;
    }

    public ArrayList<DBModel> getShareGroupDocs(String groupName) {
        ArrayList<DBModel> arrayList = new ArrayList<>();
        for (DBModel dBModel : getGroupDocs(groupName)) {
            if (dBModel.getGroup_doc_name() != null) arrayList.add(dBModel);
        }
        return arrayList;
    }


    public String getSingleNote(String str, String str2) {
        SQLiteDatabase readableDatabase = getReadableDatabase();
        GroupRef group = findGroup(readableDatabase, str);
        if (group == null) {
            return "";
        }
        Cursor rawQuery = readableDatabase.rawQuery("SELECT  * FROM " + quote(group.table) + " WHERE " + KEY_IMG_NAME + " = ?", new String[]{str2});
        try {
            if (!rawQuery.moveToFirst()) {
                return "";
            }
            return rawQuery.getString(2);
        } finally {
            rawQuery.close();
        }
    }

    /** Removes the folder row, and its page table unless another (version 1) folder shares it. */
    private static void deleteGroup(SQLiteDatabase db, GroupRef group) {
        db.delete(TABLE_NAME, "id = ?", new String[]{String.valueOf(group.id)});
        Cursor cursor = db.rawQuery("SELECT 1 FROM alldocs WHERE tbl = ? COLLATE NOCASE LIMIT 1", new String[]{group.table});
        boolean stillUsed;
        try {
            stillUsed = cursor.moveToFirst();
        } finally {
            cursor.close();
        }
        if (!stillUsed && !isReservedTable(group.table)) {
            db.execSQL("DROP TABLE IF EXISTS " + quote(group.table));
        }
    }

    private static ArrayList<String> imagePaths(SQLiteDatabase db, String table, String where, String[] args) {
        ArrayList<String> paths = new ArrayList<>();
        Cursor cursor = db.rawQuery("SELECT " + KEY_IMG_PATH + " FROM " + quote(table) + (where == null ? "" : " WHERE " + where), args);
        try {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) {
                    paths.add(cursor.getString(0));
                }
            }
        } finally {
            cursor.close();
        }
        return paths;
    }

    /** True while any folder still has a page pointing at {@code path}. Errs on the side of true. */
    private static boolean isImageReferenced(SQLiteDatabase db, String path) {
        Cursor tables = db.rawQuery("SELECT DISTINCT tbl FROM alldocs WHERE tbl IS NOT NULL", null);
        try {
            while (tables.moveToNext()) {
                String table = tables.getString(0);
                if (isReservedTable(table)) {
                    continue;
                }
                try {
                    Cursor cursor = db.rawQuery("SELECT 1 FROM " + quote(table) + " WHERE " + KEY_IMG_PATH + " = ? LIMIT 1", new String[]{path});
                    try {
                        if (cursor.moveToFirst()) {
                            return true;
                        }
                    } finally {
                        cursor.close();
                    }
                } catch (SQLiteException e) {
                    return true;
                }
            }
        } finally {
            tables.close();
        }
        return false;
    }

    /**
     * Of {@code paths}, returns the image files that no page references any more and that lie
     * inside the app's own storage. Deleting a page used to leave its image (scanned IDs,
     * signatures) on disk for good.
     */
    private ArrayList<File> unreferencedImages(SQLiteDatabase db, ArrayList<String> paths) {
        ArrayList<File> files = new ArrayList<>();
        if (imageRoot == null) {
            return files;
        }
        try {
            String root = imageRoot.getCanonicalPath() + File.separator;
            for (String path : paths) {
                File file = new File(path);
                if (file.getCanonicalPath().startsWith(root) && !files.contains(file) && !isImageReferenced(db, path)) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot resolve image paths", e);
            files.clear();
        }
        return files;
    }

    private static void deleteFiles(ArrayList<File> files) {
        for (File file : files) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Cannot delete " + file.getName());
            }
        }
    }

    public void deleteGroup(String str) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ArrayList<File> orphans = new ArrayList<>();
        writableDatabase.beginTransaction();
        try {
            GroupRef group = findGroup(writableDatabase, str);
            if (group != null) {
                ArrayList<String> paths = imagePaths(writableDatabase, group.table, null, null);
                deleteGroup(writableDatabase, group);
                orphans = unreferencedImages(writableDatabase, paths);
            }
            writableDatabase.setTransactionSuccessful();
        } finally {
            writableDatabase.endTransaction();
            writableDatabase.close();
        }
        // Only after the rows are gone for good.
        deleteFiles(orphans);
    }

    public void deleteSingleDoc(String str, String str2) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ArrayList<File> orphans = new ArrayList<>();
        writableDatabase.beginTransaction();
        try {
            GroupRef group = findGroup(writableDatabase, str);
            if (group != null) {
                String[] args = new String[]{String.valueOf(str2)};
                ArrayList<String> paths = imagePaths(writableDatabase, group.table, "imgname = ?", args);
                writableDatabase.delete(quote(group.table), "imgname = ?", args);
                orphans = unreferencedImages(writableDatabase, paths);
            }
            writableDatabase.setTransactionSuccessful();
        } finally {
            writableDatabase.endTransaction();
            writableDatabase.close();
        }
        deleteFiles(orphans);
    }

    /**
     * Renames a folder. Returns false (after showing a toast) when the new name is invalid or
     * already used by another folder; callers must then keep using the old name.
     */
    public boolean updateGroupName(Activity activity, String str, String str2) {
        if (!isValidGroupName(str2)) {
            Toast.makeText(activity, "Invalid document name", Toast.LENGTH_SHORT).show();
            return false;
        }
        SQLiteDatabase writableDatabase = getWritableDatabase();
        try {
            if (isGroupNameTaken(writableDatabase, str2, str)) {
                Toast.makeText(activity, "Document Name Already Exist", Toast.LENGTH_SHORT).show();
                return false;
            }
            GroupRef group = findGroup(writableDatabase, str);
            if (group == null) {
                return false;
            }
            // The page table is keyed by id, so only the display name changes.
            ContentValues contentValues = new ContentValues();
            contentValues.put("name", str2);
            return writableDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(group.id)}) > 0;
        } finally {
            writableDatabase.close();
        }
    }

    public void updateGroupFirstImg(String str, String str2) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        GroupRef group = findGroup(writableDatabase, str);
        if (group != null) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(KEY_FIRST_IMAGE, str2);
            writableDatabase.update(TABLE_NAME, contentValues, "id = ?", new String[]{String.valueOf(group.id)});
        }
        writableDatabase.close();
    }

    /** Points a page at its new (edited/rotated) image and deletes the old image if now unused. */
    public void updateGroupListDoc(String str, String str2, String str3) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        ArrayList<File> orphans = new ArrayList<>();
        writableDatabase.beginTransaction();
        try {
            GroupRef group = findGroup(writableDatabase, str);
            if (group != null) {
                String[] args = new String[]{String.valueOf(str2)};
                ArrayList<String> oldPaths = imagePaths(writableDatabase, group.table, "imgname = ?", args);
                oldPaths.remove(str3);
                ContentValues contentValues = new ContentValues();
                contentValues.put(KEY_IMG_PATH, str3);
                writableDatabase.update(quote(group.table), contentValues, "imgname = ?", args);
                orphans = unreferencedImages(writableDatabase, oldPaths);
            }
            writableDatabase.setTransactionSuccessful();
        } finally {
            writableDatabase.endTransaction();
            writableDatabase.close();
        }
        deleteFiles(orphans);
    }

    public void updateGroupListDocNote(String str, String str2, String str3) {
        SQLiteDatabase writableDatabase = getWritableDatabase();
        GroupRef group = findGroup(writableDatabase, str);
        if (group != null) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(KEY_IMG_NOTE, str3);
            writableDatabase.update(quote(group.table), contentValues, "imgname = ?", new String[]{String.valueOf(str2)});
        }
        writableDatabase.close();
    }
}
