package io.github.zensu357.camswap;

import android.util.Log;

import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * USB 采集卡配置的 JSON 序列化 / 反序列化测试。
 */
public class UsbCaptureConfigJvmTest {

    private MockedStatic<Log> mockLog() {
        MockedStatic<Log> logMock = Mockito.mockStatic(Log.class);
        logMock.when(() -> Log.i(Mockito.anyString(), Mockito.anyString())).thenReturn(0);
        return logMock;
    }

    @Test
    public void defaults_matchSpecifiedValues() {
        UsbCaptureConfig config = UsbCaptureConfig.defaults();
        assertEquals(1280, config.width);
        assertEquals(720, config.height);
        assertEquals(30, config.fps);
        assertTrue(config.autoReconnect);
        assertFalse(config.hasExplicitDevice());
    }

    @Test
    public void sourceTypeAliasStaysInSync() {
        assertEquals("usb_capture", ConfigManager.MEDIA_SOURCE_USB);
        assertEquals(ConfigManager.MEDIA_SOURCE_USB, ConfigManager.SOURCE_TYPE_USB);
    }

    @Test
    public void toJson_writesAllUsbKeys() throws Exception {
        UsbCaptureConfig config =
                new UsbCaptureConfig("/dev/bus/usb/001/003", 1920, 1080, 60, false);
        JSONObject json = config.toJson();

        assertEquals(ConfigManager.MEDIA_SOURCE_USB,
                json.getString(ConfigManager.KEY_MEDIA_SOURCE_TYPE));
        assertEquals("/dev/bus/usb/001/003", json.getString(ConfigManager.KEY_USB_DEVICE_NAME));
        assertEquals(1920, json.getInt(ConfigManager.KEY_USB_WIDTH));
        assertEquals(1080, json.getInt(ConfigManager.KEY_USB_HEIGHT));
        assertEquals(60, json.getInt(ConfigManager.KEY_USB_FPS));
        assertFalse(json.getBoolean(ConfigManager.KEY_USB_AUTO_RECONNECT));
    }

    @Test
    public void fromJson_roundTripsThroughToJson() {
        UsbCaptureConfig original =
                new UsbCaptureConfig("/dev/bus/usb/002/004", 1280, 960, 25, true);
        UsbCaptureConfig parsed = UsbCaptureConfig.fromJsonString(original.toJson().toString());
        assertEquals(original, parsed);
    }

    @Test
    public void fromJson_missingFieldsFallBackToDefaults() throws Exception {
        // 只写了 media_source_type，其它 usb_* 字段缺失
        JSONObject partial = new JSONObject()
                .put(ConfigManager.KEY_MEDIA_SOURCE_TYPE, ConfigManager.MEDIA_SOURCE_USB);
        UsbCaptureConfig parsed = UsbCaptureConfig.fromJson(partial);
        assertEquals(UsbCaptureConfig.defaults(), parsed);
    }

    @Test
    public void fromJson_illegalValuesAreClamped() throws Exception {
        JSONObject bad = new JSONObject()
                .put(ConfigManager.KEY_USB_WIDTH, 0)
                .put(ConfigManager.KEY_USB_HEIGHT, -1)
                .put(ConfigManager.KEY_USB_FPS, 100000);
        UsbCaptureConfig parsed = UsbCaptureConfig.fromJson(bad);
        assertEquals(ConfigManager.DEFAULT_USB_WIDTH, parsed.width);
        assertEquals(ConfigManager.DEFAULT_USB_HEIGHT, parsed.height);
        assertEquals(ConfigManager.DEFAULT_USB_FPS, parsed.fps);
    }

    @Test
    public void fromJsonString_invalidJsonReturnsDefaults() {
        assertEquals(UsbCaptureConfig.defaults(), UsbCaptureConfig.fromJsonString("not-a-json"));
        assertEquals(UsbCaptureConfig.defaults(), UsbCaptureConfig.fromJsonString(null));
    }

    @Test
    public void configManager_readsUsbKeysFromBroadcastJson() {
        try (MockedStatic<Log> ignored = mockLog()) {
            ConfigManager configManager = new ConfigManager(false);
            configManager.updateConfigFromJSON("{"
                    + "\"media_source_type\":\"usb_capture\","
                    + "\"usb_device_name\":\"/dev/bus/usb/001/005\","
                    + "\"usb_width\":1920,"
                    + "\"usb_height\":1080,"
                    + "\"usb_fps\":60,"
                    + "\"usb_auto_reconnect\":false"
                    + "}");

            assertTrue(configManager.isUsbCaptureMode());
            UsbCaptureConfig config = configManager.getUsbCaptureConfig();
            assertEquals("/dev/bus/usb/001/005", config.deviceName);
            assertEquals(1920, config.width);
            assertEquals(1080, config.height);
            assertEquals(60, config.fps);
            assertFalse(config.autoReconnect);
        }
    }

    @Test
    public void configManager_defaultsWhenUsbKeysAbsent() {
        try (MockedStatic<Log> ignored = mockLog()) {
            ConfigManager configManager = new ConfigManager(false);
            configManager.updateConfigFromJSON("{\"media_source_type\":\"local\"}");

            assertFalse(configManager.isUsbCaptureMode());
            assertEquals(UsbCaptureConfig.defaults(), configManager.getUsbCaptureConfig());
        }
    }

    @Test
    public void configManager_exportAndImportUsbConfig() {
        try (MockedStatic<Log> ignored = mockLog()) {
            ConfigManager configManager = new ConfigManager(false);
            configManager.updateConfigFromJSON("{\"selected_video\":\"demo.mp4\"}");

            String exported = configManager.exportUsbConfig();
            assertNotNull(exported);

            assertTrue(configManager.importUsbConfig("{"
                    + "\"usb_device_name\":\"/dev/bus/usb/003/002\","
                    + "\"usb_width\":640,"
                    + "\"usb_height\":480,"
                    + "\"usb_fps\":15,"
                    + "\"usb_auto_reconnect\":true"
                    + "}"));

            UsbCaptureConfig config = configManager.getUsbCaptureConfig();
            assertEquals("/dev/bus/usb/003/002", config.deviceName);
            assertEquals(640, config.width);
            assertEquals(480, config.height);
            assertEquals(15, config.fps);
            assertTrue(config.autoReconnect);

            // 导入 USB 配置不应破坏其它配置项
            assertEquals("demo.mp4", configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null));

            assertFalse(configManager.importUsbConfig("}{ broken"));
        }
    }

    @Test
    public void mediaSourceDescriptor_usbCaptureIsValid() {
        MediaSourceDescriptor descriptor =
                MediaSourceDescriptor.usbCapture(UsbCaptureConfig.defaults()).build();
        assertTrue(descriptor.isUsbCapture());
        assertFalse(descriptor.isStream());
        assertTrue(descriptor.isValid());
        assertEquals(MediaSourceDescriptor.Type.USB_CAPTURE, descriptor.type);
    }

    @Test
    public void ipcContract_usbConstantsAreConsistent() {
        assertEquals("io.github.zensu357.camswap", IpcContract.HOST_PACKAGE_NAME);
        assertEquals("io.github.zensu357.camswap.UsbCaptureService", IpcContract.USB_SERVICE_CLASS_NAME);
        assertEquals(UsbCaptureService.class.getName(), IpcContract.USB_SERVICE_CLASS_NAME);
    }
}
