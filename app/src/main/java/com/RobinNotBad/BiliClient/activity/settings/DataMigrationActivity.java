package com.RobinNotBad.BiliClient.activity.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.RobinNotBad.BiliClient.R;
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity;
import com.RobinNotBad.BiliClient.util.JsonUtil;
import com.RobinNotBad.BiliClient.util.MsgUtil;
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DataMigrationActivity extends InstanceActivity {
    
    private static final int PICK_IMPORT_SEARCH_HISTORY_REQUEST = 1001;
    private static final int PICK_IMPORT_SETTINGS_REQUEST = 1002;
    
    private int currentImportType = 0; // 0=未知, 1=搜索记录, 2=设置
    
    @SuppressLint({"MissingInflatedId", "InflateParams"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        asyncInflate(R.layout.activity_data_migration, ((layoutView, id) -> {
            Log.e("debug", "进入数据迁移页面");
            
            // 导出搜索记录
            MaterialCardView exportSearchHistory = findViewById(R.id.export_search_history);
            exportSearchHistory.setOnClickListener(view -> exportSearchHistory());
            
            // 导出所有设置
            MaterialCardView exportAllSettings = findViewById(R.id.export_all_settings);
            exportAllSettings.setOnClickListener(view -> exportAllSettings());
            
            // 导入搜索记录
            MaterialCardView importSearchHistory = findViewById(R.id.import_search_history);
            importSearchHistory.setOnClickListener(view -> importSearchHistory());
            
            // 导入设置
            MaterialCardView importSettings = findViewById(R.id.import_settings);
            importSettings.setOnClickListener(view -> importSettings());
            
            // 手动导入（从旧版本）
            MaterialCardView manualImport = findViewById(R.id.manual_import);
            manualImport.setOnClickListener(view -> manualImport());
            
            // 返回按钮
            MaterialCardView backButton = findViewById(R.id.back_button);
            backButton.setOnClickListener(view -> finish());
        }));
    }
    
    /**
     * 导出搜索记录
     */
    private void exportSearchHistory() {
        try {
            String historyJson = SharedPreferencesUtil.getString(
                SharedPreferencesUtil.search_history, "[]");
            JSONArray historyArray = new JSONArray(historyJson);
            
            if (historyArray.length() == 0) {
                MsgUtil.showMsg("没有搜索记录可以导出");
                return;
            }
            
            // 创建导出数据
            JSONObject exportData = new JSONObject();
            exportData.put("type", "search_history");
            exportData.put("version", "1.0");
            exportData.put("export_time", System.currentTimeMillis());
            exportData.put("data", historyArray);
            
            // 保存到文件
            String exportJson = exportData.toString(2); // 缩进2空格
            String fileName = "BiliClient_SearchHistory_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";
            
            File exportDir = new File(getExternalFilesDir(null), "backups");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            File exportFile = new File(exportDir, fileName);
            FileWriter writer = new FileWriter(exportFile);
            writer.write(exportJson);
            writer.close();
            
            // 分享文件
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            Uri fileUri = FileProvider.getUriForFile(this, 
                "com.RobinNotBad.BiliClient.FileProvider", exportFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "导出搜索记录"));
            
            MsgUtil.showMsg("搜索记录已导出到: " + exportFile.getAbsolutePath());
            
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtil.showMsg("导出失败: " + e.getMessage());
        }
    }
    
    /**
     * 导出所有设置
     */
    private void exportAllSettings() {
        try {
            JSONObject exportData = new JSONObject();
            
            // 导出所有SharedPreferences数据
            Map<String, ?> allPrefs = SharedPreferencesUtil.getSharedPreferences().getAll();
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                // 跳过敏感数据（可选）
                if (key.contains("password") || key.contains("token") || key.contains("secret")) {
                    continue;
                }
                
                if (value instanceof String) {
                    exportData.put(key, (String) value);
                } else if (value instanceof Integer) {
                    exportData.put(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    exportData.put(key, (Boolean) value);
                } else if (value instanceof Long) {
                    exportData.put(key, (Long) value);
                } else if (value instanceof Float) {
                    exportData.put(key, (Float) value);
                }
            }
            
            // 添加元数据
            JSONObject metaData = new JSONObject();
            metaData.put("type", "all_settings");
            metaData.put("version", "1.0");
            metaData.put("export_time", System.currentTimeMillis());
            metaData.put("item_count", allPrefs.size());
            exportData.put("_metadata", metaData);
            
            // 保存到文件
            String exportJson = exportData.toString(2);
            String fileName = "BiliClient_Settings_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";
            
            File exportDir = new File(getExternalFilesDir(null), "backups");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            File exportFile = new File(exportDir, fileName);
            FileWriter writer = new FileWriter(exportFile);
            writer.write(exportJson);
            writer.close();
            
            // 分享文件
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            Uri fileUri = FileProvider.getUriForFile(this, 
                "com.RobinNotBad.BiliClient.FileProvider", exportFile);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "导出设置"));
            
            MsgUtil.showMsg("设置已导出到: " + exportFile.getAbsolutePath());
            
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtil.showMsg("导出失败: " + e.getMessage());
        }
    }
    
    /**
     * 导入搜索记录
     */
    private void importSearchHistory() {
        currentImportType = 1; // 搜索记录
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMPORT_SEARCH_HISTORY_REQUEST);
    }
    
    /**
     * 导入设置
     */
    private void importSettings() {
        currentImportType = 2; // 设置
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMPORT_SETTINGS_REQUEST);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if ((requestCode == PICK_IMPORT_SEARCH_HISTORY_REQUEST || requestCode == PICK_IMPORT_SETTINGS_REQUEST) 
                && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            
            // 显示加载提示
            MsgUtil.showMsg("正在读取文件...");
            
            // 在后台线程中处理文件读取，避免ANR
            new Thread(() -> {
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder stringBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stringBuilder.append(line);
                    }
                    
                    String jsonContent = stringBuilder.toString();
                    
                    // 回到主线程处理结果
                    runOnUiThread(() -> {
                        try {
                            // 验证JSON格式
                            if (!isValidJson(jsonContent)) {
                                MsgUtil.showMsg("文件格式错误：不是有效的JSON文件");
                                return;
                            }
                            
                            JSONObject importData = new JSONObject(jsonContent);
                            
                            // 根据请求类型验证文件内容
                            if (requestCode == PICK_IMPORT_SEARCH_HISTORY_REQUEST) {
                                // 用户想要导入搜索记录，验证文件类型
                                if (importData.has("type") && "search_history".equals(importData.getString("type"))) {
                                    // 验证搜索记录文件结构
                                    if (!importData.has("data") || !(importData.get("data") instanceof JSONArray)) {
                                        MsgUtil.showMsg("文件格式错误：搜索记录文件缺少data字段或格式不正确");
                                        return;
                                    }
                                    importSearchHistoryData(importData);
                                } else if (importData.has("data") && importData.get("data") instanceof JSONArray) {
                                    // 可能是搜索记录文件（没有type字段）
                                    importSearchHistoryData(importData);
                                } else {
                                    MsgUtil.showMsg("错误：您选择的是设置文件，请使用'导入设置'功能");
                                }
                            } else if (requestCode == PICK_IMPORT_SETTINGS_REQUEST) {
                                // 用户想要导入设置，验证文件类型
                                if (importData.has("type") && "all_settings".equals(importData.getString("type"))) {
                                    // 验证设置文件结构
                                    if (importData.length() < 2) { // 至少包含type和_metadata
                                        MsgUtil.showMsg("文件格式错误：设置文件内容过少");
                                        return;
                                    }
                                    importAllSettingsData(importData);
                                } else if (importData.has("_metadata")) {
                                    // 可能是设置文件（没有type字段）
                                    importAllSettingsData(importData);
                                } else if (importData.length() > 0) {
                                    // 可能是旧格式的设置文件
                                    importAllSettingsData(importData);
                                } else {
                                    MsgUtil.showMsg("错误：您选择的是搜索记录文件，请使用'导入搜索记录'功能");
                                }
                            }
                            
                        } catch (JSONException e) {
                            e.printStackTrace();
                            MsgUtil.showMsg("JSON解析失败：文件格式不正确");
                        } catch (Exception e) {
                            e.printStackTrace();
                            MsgUtil.showMsg("导入失败: " + e.getMessage());
                        } finally {
                            currentImportType = 0; // 重置导入类型
                        }
                    });
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        MsgUtil.showMsg("文件读取失败: " + e.getMessage());
                        currentImportType = 0; // 重置导入类型
                    });
                }
            }).start();
        }
    }
    
    /**
     * 验证JSON字符串是否有效
     */
    private boolean isValidJson(String json) {
        try {
            new JSONObject(json);
            return true;
        } catch (JSONException e1) {
            try {
                new JSONArray(json);
                return true;
            } catch (JSONException e2) {
                return false;
            }
        }
    }
    
    /**
     * 导入搜索记录数据
     */
    private void importSearchHistoryData(JSONObject importData) throws JSONException {
        if (!importData.has("data")) {
            MsgUtil.showMsg("文件格式错误：缺少data字段");
            return;
        }
        
        JSONArray historyArray = importData.getJSONArray("data");
        if (historyArray.length() == 0) {
            MsgUtil.showMsg("文件为空或没有搜索记录");
            return;
        }
        
        // 获取现有搜索记录
        String existingHistoryJson = SharedPreferencesUtil.getString(
            SharedPreferencesUtil.search_history, "[]");
        JSONArray existingArray = new JSONArray(existingHistoryJson);
        
        // 合并搜索记录（去重）
        List<String> mergedList = new ArrayList<>();
        
        // 先添加导入的记录
        for (int i = 0; i < historyArray.length(); i++) {
            String item = historyArray.getString(i);
            if (!mergedList.contains(item)) {
                mergedList.add(item);
            }
        }
        
        // 再添加现有记录（避免重复）
        for (int i = 0; i < existingArray.length(); i++) {
            String item = existingArray.getString(i);
            if (!mergedList.contains(item)) {
                mergedList.add(item);
            }
        }
        
        // 保存合并后的记录
        JSONArray mergedArray = new JSONArray(mergedList);
        SharedPreferencesUtil.putString(
            SharedPreferencesUtil.search_history, 
            mergedArray.toString());
        
        MsgUtil.showMsg("搜索记录导入成功，共导入 " + historyArray.length() + " 条记录，合并后共 " + mergedList.size() + " 条记录");
    }
    
    /**
     * 导入所有设置数据（安全版本 - 选择性导入）
     * 只导入安全的设置，跳过所有可能引起问题的设置
     */
    private void importAllSettingsData(JSONObject importData) throws JSONException {
        // 直接执行安全导入，不显示确认对话框
        // 因为我们已经有了白名单机制，只导入安全的设置
        performSafeImport(importData);
    }
    
    /**
     * 执行安全导入操作
     */
    private void performSafeImport(JSONObject importData) {
        try {
            // 1. 备份当前设置
            Map<String, ?> currentSettings = SharedPreferencesUtil.getSharedPreferences().getAll();
            JSONObject backupData = new JSONObject();
            for (Map.Entry<String, ?> entry : currentSettings.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value instanceof String) {
                    backupData.put(key, (String) value);
                } else if (value instanceof Integer) {
                    backupData.put(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    backupData.put(key, (Boolean) value);
                } else if (value instanceof Long) {
                    backupData.put(key, (Long) value);
                } else if (value instanceof Float) {
                    backupData.put(key, (Float) value);
                }
            }
            
            // 2. 创建备份文件
            File backupDir = new File(getExternalFilesDir(null), "backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }
            
            String backupFileName = "settings_backup_" + 
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";
            File backupFile = new File(backupDir, backupFileName);
            
            try {
                FileWriter backupWriter = new FileWriter(backupFile);
                backupWriter.write(backupData.toString(2));
                backupWriter.close();
            } catch (IOException e) {
                Log.e("DataMigration", "创建备份文件失败", e);
            }
            
            // 3. 定义安全的白名单设置键（只导入这些安全的设置）
            List<String> safeKeys = Arrays.asList(
                // 界面设置
                "app_theme", "language", "font_size", "ui_mode",
                "paddingH_percent", "paddingV_percent", "dpi",
                "home_feed_column",
                
                // 播放器设置
                "player", "player_scale", "player_longclick", "player_doublemove",
                "player_from_last", "player_ui_showDanmakuBtn", "player_ui_showPageBtn",
                "player_ui_showQualityBtn", "player_ui_showRotateBtn",
                "player_danmaku_allowoverlap", "player_danmaku_forceR2L",
                "player_subtitle_ai_allowed", "pref_switch_danmaku",
                
                // 功能开关
                "menu_sort", "menu_precious", "menu_popular", "menu_live",
                "auto_check_update_enable",
                
                // 教程状态
                "tutorial_ver_search", "tutorial_ver_video", "tutorial_ver_space",
                "tutorial_ver_dynamic", "tutorial_ver_message", "tutorial_ver_recommend",
                "tutorial_pager_OpusInfoActivity", "tutorial_pager_VideoInfoActivity",
                "tutorial_pager_SearchActivity", "tutorial_pager_UserInfoActivity",
                "tutorial_pager_DynamicInfoActivity",
                
                // 其他安全设置
                "first_play", "disclaimer_shown", "setup",
                "app_announcement_last", "app_version_check", "app_version_last",
                "last_wbi", "message_update_num", "dynamic_update_num"
            );
            
            // 4. 安全导入设置（只导入白名单中的设置）
            SharedPreferences.Editor editor = SharedPreferencesUtil.getSharedPreferences().edit();
            int importedCount = 0;
            int skippedCount = 0;
            
            Iterator<String> keys = importData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                
                // 跳过元数据字段
                if ("_metadata".equals(key)) {
                    continue;
                }
                
                // 只导入白名单中的安全键
                if (!safeKeys.contains(key)) {
                    Log.w("DataMigration", "跳过非白名单键: " + key);
                    skippedCount++;
                    continue;
                }
                
                Object value = importData.get(key);
                
                try {
                    // 智能类型处理：根据键名和值决定存储类型
                    if (value instanceof String) {
                        String strValue = (String) value;
                        // 验证字符串长度（防止过长的值）
                        if (strValue.length() > 5000) {
                            Log.w("DataMigration", "跳过过长的字符串值: " + key);
                            skippedCount++;
                            continue;
                        }
                        editor.putString(key, strValue);
                        
                    } else if (value instanceof Boolean) {
                        editor.putBoolean(key, (Boolean) value);
                        
                    } else if (value instanceof Number) {
                        // 数字类型需要根据键名决定存储类型
                        Number numberValue = (Number) value;
                        
                        // 特殊处理：某些键需要特定的类型
                        if ("dpi".equals(key)) {
                            // dpi应该存储为Float类型（从BiliTerminal.getFitDisplayContext()看）
                            float floatValue = numberValue.floatValue();
                            editor.putFloat(key, floatValue);
                            Log.i("DataMigration", "特殊处理Float(dpi): " + key + " = " + floatValue);
                            
                        } else if ("paddingH_percent".equals(key) || "paddingV_percent".equals(key)) {
                            // paddingH_percent和paddingV_percent应该存储为Integer类型
                            int intValue = numberValue.intValue();
                            editor.putInt(key, intValue);
                            Log.i("DataMigration", "特殊处理Integer(padding): " + key + " = " + intValue);
                            
                        } else if (key.contains("_ver_") || key.contains("_num") || key.contains("_check") || 
                                  key.contains("_last") || key.contains("_column") || key.equals("last_wbi")) {
                            // 版本号、数量、检查、最后等键应该存储为Integer
                            int intValue = numberValue.intValue();
                            // 验证整数范围
                            if (intValue < -1000000 || intValue > 1000000) {
                                Log.w("DataMigration", "跳过超出范围的整数值: " + key + " = " + intValue);
                                skippedCount++;
                                continue;
                            }
                            editor.putInt(key, intValue);
                            Log.i("DataMigration", "存储为Integer: " + key + " = " + intValue);
                            
                        } else if (numberValue instanceof Integer) {
                            int intValue = (Integer) value;
                            // 验证整数范围
                            if (intValue < -1000000 || intValue > 1000000) {
                                Log.w("DataMigration", "跳过超出范围的整数值: " + key + " = " + intValue);
                                skippedCount++;
                                continue;
                            }
                            editor.putInt(key, intValue);
                            
                        } else if (numberValue instanceof Long) {
                            long longValue = (Long) value;
                            // 验证长整数范围
                            if (longValue < -1000000000L || longValue > 1000000000L) {
                                Log.w("DataMigration", "跳过超出范围的长整数值: " + key + " = " + longValue);
                                skippedCount++;
                                continue;
                            }
                            editor.putLong(key, longValue);
                            
                        } else if (numberValue instanceof Float) {
                            float floatValue = (Float) value;
                            // 验证浮点数范围
                            if (floatValue < -1000000.0f || floatValue > 1000000.0f) {
                                Log.w("DataMigration", "跳过超出范围的浮点数值: " + key + " = " + floatValue);
                                skippedCount++;
                                continue;
                            }
                            editor.putFloat(key, floatValue);
                            
                        } else {
                            // 其他数字类型转为Integer
                            int intValue = numberValue.intValue();
                            if (intValue < -1000000 || intValue > 1000000) {
                                Log.w("DataMigration", "跳过超出范围的转换值: " + key + " = " + intValue);
                                skippedCount++;
                                continue;
                            }
                            editor.putInt(key, intValue);
                            Log.i("DataMigration", "数字转为Integer: " + key + " = " + intValue);
                        }
                        
                    } else {
                        // 其他类型转为字符串（有限制）
                        String strValue = value.toString();
                        if (strValue.length() > 1000) {
                            Log.w("DataMigration", "跳过过长的转换值: " + key);
                            skippedCount++;
                            continue;
                        }
                        editor.putString(key, strValue);
                    }
                    
                    importedCount++;
                    
                } catch (Exception e) {
                    skippedCount++;
                    Log.e("DataMigration", "导入键值对失败: " + key + " = " + value, e);
                }
            }
            
            // 5. 应用设置
            editor.apply();
            
            // 6. 显示导入结果
            String message = "设置导入完成\n\n" +
                    "已导入 " + importedCount + " 项安全设置\n" +
                    "跳过 " + skippedCount + " 项不安全设置\n\n" +
                    "备份文件已保存到:\n" + backupFile.getAbsolutePath() + "\n\n" +
                    "如果应用出现问题，可以手动恢复备份文件。";
            
            MsgUtil.showText("导入完成", message);
            
        } catch (Exception e) {
            Log.e("DataMigration", "导入设置失败", e);
            MsgUtil.showMsg("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证设置是否有效
     */
    private boolean validateSettings() {
        try {
            // 尝试读取一些关键设置，验证是否有效
            SharedPreferences prefs = SharedPreferencesUtil.getSharedPreferences();
            
            // 验证应用主题
            String theme = prefs.getString("app_theme", "default");
            if (theme != null && theme.length() > 100) {
                return false;
            }
            
            // 验证语言设置
            String language = prefs.getString("language", "zh");
            if (language != null && language.length() > 10) {
                return false;
            }
            
            // 验证字体大小
            int fontSize = prefs.getInt("font_size", 14);
            if (fontSize < 8 || fontSize > 72) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e("DataMigration", "设置验证失败", e);
            return false;
        }
    }
    
    /**
     * 回滚设置到备份状态
     */
    private void rollbackSettings(JSONObject backupData) {
        try {
            SharedPreferences.Editor editor = SharedPreferencesUtil.getSharedPreferences().edit();
            editor.clear(); // 清空所有设置
            
            Iterator<String> keys = backupData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = backupData.get(key);
                
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                }
            }
            
            editor.apply();
            Log.i("DataMigration", "设置回滚完成");
            
        } catch (Exception e) {
            Log.e("DataMigration", "设置回滚失败", e);
        }
    }
    
    /**
     * 手动导入（从旧版本）
     * 用于从没有数据迁移功能的旧版本导入数据
     */
    private void manualImport() {
        // 显示手动导入说明
        String message = "手动导入说明：\n\n" +
                "1. 从旧版本设备导出数据：\n" +
                "   - 使用文件管理器找到旧版本的数据文件\n" +
                "   - 路径：/data/data/com.RobinNotBad.BiliClient/shared_prefs/\n" +
                "   - 复制以下文件：\n" +
                "     • BiliTerminal.xml（主设置文件）\n" +
                "     • 其他相关XML文件\n\n" +
                "2. 将文件传输到新设备\n\n" +
                "3. 在新设备上选择导入文件\n\n" +
                "注意：需要root权限或ADB备份功能";
        
        MsgUtil.showText("手动导入说明", message);
        
        // 提供导入选项
        // 这里可以添加文件选择器，让用户选择XML文件
        // 但由于权限限制，通常需要用户手动操作
    }
    
    /**
     * 从XML文件导入SharedPreferences数据
     * 用于导入旧版本的XML格式设置文件
     */
    private void importFromXmlFile(Uri xmlUri) {
        try {
            // 读取XML文件
            InputStream inputStream = getContentResolver().openInputStream(xmlUri);
            // 这里需要解析XML文件并导入到SharedPreferences
            // 由于XML解析较复杂，建议用户使用JSON导出/导入功能
            
            MsgUtil.showMsg("XML导入功能开发中，建议使用JSON格式导入");
            
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtil.showMsg("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取备份目录
     */
    private File getBackupDirectory() {
        File backupDir = new File(Environment.getExternalStorageDirectory(), "BiliClient/Backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        return backupDir;
    }
    
    /**
     * 从旧版本手动迁移数据的替代方案
     */
    private void showAlternativeMigrationMethods() {
        String message = "从旧版本迁移数据的替代方案：\n\n" +
                "方案一：使用ADB备份（推荐）\n" +
                "1. 在旧设备上执行：\n" +
                "   adb backup -f backup.ab -apk com.RobinNotBad.BiliClient\n" +
                "2. 在新设备上执行：\n" +
                "   adb restore backup.ab\n\n" +
                "方案二：手动复制文件（需要root）\n" +
                "1. 旧设备：复制/data/data/com.RobinNotBad.BiliClient/shared_prefs/\n" +
                "2. 新设备：粘贴到相同路径\n\n" +
                "方案三：使用第三方备份工具\n" +
                "如钛备份、Swift Backup等";
        
        MsgUtil.showText("替代迁移方案", message);
    }
}