package com.winlator.cmod.winhandler;

import static com.winlator.cmod.inputcontrols.ExternalController.TRIGGER_IS_AXIS;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import android.os.Vibrator;
import android.os.VibrationEffect;
import android.view.InputDevice;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;

import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WinHandler {
    private static final String TAG = "WinHandler";

    private final ControllerManager controllerManager;

    // Multi-Controller Additions
    public static final int MAX_PLAYERS = 4;
    private final MappedByteBuffer[] extraGamepadBuffers = new MappedByteBuffer[MAX_PLAYERS - 1];
    private final ExternalController[] extraControllers = new ExternalController[MAX_PLAYERS - 1];
    private MappedByteBuffer gamepadBuffer;

    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;

//    public static final byte FLAG_DINPUT_MAPPER_STANDARD = 0x01;
//    public static final byte FLAG_DINPUT_MAPPER_XINPUT = 0x02;
    public static final byte FLAG_INPUT_TYPE_XINPUT = 0x04;
//    public static final byte FLAG_INPUT_TYPE_DINPUT = 0x08;
    public static final byte DEFAULT_INPUT_TYPE = FLAG_INPUT_TYPE_XINPUT;
//    public static final byte INPUT_TYPE_MIXED = 2;
    private DatagramSocket socket;
    private final ByteBuffer sendData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), 64);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), 64);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean initReceived = false;
    private boolean running = false;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private ExternalController currentController;
    private InetAddress localhost;
    private byte inputType = DEFAULT_INPUT_TYPE;
    private final XServerDisplayActivity activity;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();
    private SharedPreferences preferences;
    private byte triggerType;

    private boolean xinputDisabled; // Used for exclusive mouse control
    private boolean xinputDisabledInitialized = false;


//    private boolean useLegacyInputMethod = false;
//    public static final byte DINPUT_MAPPER_TYPE_STANDARD = 0;
//    public static final byte DINPUT_MAPPER_TYPE_XINPUT = 1;
//    private byte dinputMapperType = DINPUT_MAPPER_TYPE_XINPUT;


    // Gyro related variables
    private float gyroX = 0;
    private float gyroY = 0;
    // Add fields for sensitivity, smoothing, and inversion
    private float gyroSensitivityX = 0.35f;
    private float gyroSensitivityY = 0.25f;
    private float smoothingFactor = 0.45f; // For exponential smoothing
    private boolean invertGyroX = true;
    private boolean invertGyroY = false;
    private float gyroDeadzone = 0.01f;

    // Implement exponential smoothing
    private float smoothGyroX = 0;
    private float smoothGyroY = 0;

    private boolean processGyroWithLeftTrigger = false;

    private int gyroTriggerButton;
    private boolean isGyroActive = false;
    private boolean isToggleMode;

    // Rumble related variables
    private Thread rumblePollerThread;
    private short lastLowFreq = 0;  // Use 'short' instead of uint16_t
    private short lastHighFreq = 0; // Use 'short' instead of uint16_t
    private boolean isRumbling = false;

    private boolean isShowingAssignDialog = false;
    private final java.util.Set<Integer> ignoredDeviceIds = new java.util.HashSet<>();


//    public void setUseLegacyInputMethod(boolean useLegacy) {
//        this.useLegacyInputMethod = useLegacy;
//    }

    public void setGyroSensitivityX(float sensitivity) {
        this.gyroSensitivityX = sensitivity;
    }

    public void setGyroSensitivityY(float sensitivity) {
        this.gyroSensitivityY = sensitivity;
    }

    public void setSmoothingFactor(float factor) {
        this.smoothingFactor = factor;
    }

    public void setInvertGyroX(boolean invert) {
        this.invertGyroX = invert;
    }

    public void setInvertGyroY(boolean invert) {
        this.invertGyroY = invert;
    }

    public void setGyroDeadzone(float deadzone) {
        this.gyroDeadzone = deadzone;
    }



    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
        preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());
        this.controllerManager = ControllerManager.getInstance();
    }
    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0) return false;
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        command = command.trim();
        if (command.isEmpty()) return;

        // The `split` function here should be sensitive to paths with spaces.
        // Instead of splitting, let's assume that command is directly provided in two parts: filename and parameters.
        // Adjust command splitting based on whether it contains quotes.

        String filename;
        String parameters;

        if (command.contains("\"")) {
            // If the command is quoted, extract the quoted part as the filename
            int firstQuote = command.indexOf("\"");
            int lastQuote = command.lastIndexOf("\"");
            filename = command.substring(firstQuote + 1, lastQuote);
            if (lastQuote + 1 < command.length()) {
                parameters = command.substring(lastQuote + 1).trim();
            } else {
                parameters = "";
            }
        } else {
            // Standard split when no quotes
            String[] cmdList = command.split(" ", 2);
            filename = cmdList[0];
            if (cmdList.length > 1) {
                parameters = cmdList[1];
            } else {
                parameters = "";
            }
        }

        addAction(() -> {
            byte[] filenameBytes = filename.getBytes();
            byte[] parametersBytes = parameters.getBytes();

            sendData.rewind();
            sendData.put(RequestCodes.EXEC);
            sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            sendData.putInt(filenameBytes.length);
            sendData.putInt(parametersBytes.length);
            sendData.put(filenameBytes);
            sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }


    public void killProcess(final String processName) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KILL_PROCESS);
            byte[] bytes = processName.getBytes();
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void listProcesses() {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.LIST_PROCESSES);
            sendData.putInt(0);

            if (!sendPacket(CLIENT_PORT) && onGetProcessInfoListener != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }
        });
    }

    public void setProcessAffinity(final String processName, final int affinityMask) {
        addAction(() -> {
            byte[] bytes = processName.getBytes();
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9 + bytes.length);
            sendData.putInt(0);
            sendData.putInt(affinityMask);
            sendData.put((byte)bytes.length);
            sendData.put(bytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
            sendData.putInt(9);
            sendData.putInt(pid);
            sendData.putInt(affinityMask);
            sendData.put((byte)0);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.MOUSE_EVENT);
            sendData.putInt(10);
            sendData.putInt(flags);
            sendData.putShort((short)dx);
            sendData.putShort((short)dy);
            sendData.putShort((short)wheelDelta);
            sendData.put((byte)((flags & MouseEventFlags.MOVE) != 0 ? 1 : 0)); // cursor pos feedback
            sendPacket(CLIENT_PORT);
        });
    }

    public void keyboardEvent(byte vkey, int flags) {
        if (!initReceived) return;
        addAction(() -> {
            sendData.rewind();
            sendData.put(RequestCodes.KEYBOARD_EVENT);
            sendData.put(vkey);
            sendData.putInt(flags);
            sendPacket(CLIENT_PORT);
        });
    }

    public void bringToFront(final String processName) {
        bringToFront(processName, 0);
    }

    public void bringToFront(final String processName, final long handle) {
        addAction(() -> {
            sendData.rewind();
            try {
                sendData.put(RequestCodes.BRING_TO_FRONT);
                byte[] bytes = processName.getBytes();
                sendData.putInt(bytes.length);
                // FIXME: Chinese and Japanese got from winhandler.exe are broken, and they cause overflow.
                sendData.put(bytes);
                sendData.putLong(handle);
            } catch (java.nio.BufferOverflowException e) {
                e.printStackTrace();
                sendData.rewind();
            }
            sendPacket(CLIENT_PORT);
        });
    }

    private void addAction(Runnable action) {
        synchronized (actions) {
            actions.add(action);
            actions.notify();
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty()) actions.poll().run();
                    try {
                        actions.wait();
                    }
                    catch (InterruptedException e) {}
                }
            }
        });
    }

    public void stop() {
        running = false;

        if (socket != null) {
            socket.close();
            socket = null;
        }

        synchronized (actions) {
            actions.notify();
        }
    }

    private void handleRequest(byte requestCode, final int port) {
        switch (requestCode) {
            case RequestCodes.INIT: {
                initReceived = true;



                preferences = PreferenceManager.getDefaultSharedPreferences(activity.getBaseContext());

                gyroTriggerButton = preferences.getInt("gyro_trigger_button", KeyEvent.KEYCODE_BUTTON_L1);
                isToggleMode = preferences.getInt("gyro_mode", 0) == 1; // 1 is toggle mode, 0 is hold mode


                // Load and apply trigger mode and xinput toggle settings
                triggerType = (byte) preferences.getInt("trigger_type", TRIGGER_IS_AXIS);

                refreshControllerMappings();

                // Only set xinputDisabled if it hasn't been set explicitly by XServerDisplayActivity
                if (!xinputDisabledInitialized) {
                    xinputDisabled = preferences.getBoolean("xinput_toggle", false);
                }

                // Load the flag to use legacy input method
//                useLegacyInputMethod = preferences.getBoolean("useLegacyInputMethod", false);

                // Load and apply gyro settings
                setGyroSensitivityX(preferences.getFloat("gyro_x_sensitivity", 1.0f));
                setGyroSensitivityY(preferences.getFloat("gyro_y_sensitivity", 1.0f));
                setSmoothingFactor(preferences.getFloat("gyro_smoothing", 0.9f));
                setInvertGyroX(preferences.getBoolean("invert_gyro_x", false));
                setInvertGyroY(preferences.getBoolean("invert_gyro_y", false));
                setGyroDeadzone(preferences.getFloat("gyro_deadzone", 0.05f));

                processGyroWithLeftTrigger = preferences.getBoolean("process_gyro_with_left_trigger", false);

                synchronized (actions) {
                    actions.notify();
                }
                break;
            }

            case RequestCodes.GET_PROCESS: {
                if (onGetProcessInfoListener == null) return;
                receiveData.position(receiveData.position() + 4);
                int numProcesses = receiveData.getShort();
                int index = receiveData.getShort();
                int pid = receiveData.getInt();
                long memoryUsage = receiveData.getLong();
                int affinityMask = receiveData.getInt();
                boolean wow64Process = receiveData.get() == 1;

                byte[] bytes = new byte[32];
                receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);

                onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, new ProcessInfo(pid, name, memoryUsage, affinityMask, wow64Process));
                break;
            }
            case RequestCodes.GET_GAMEPAD: {
                if (xinputDisabled) return;
                boolean notify = receiveData.get() == 1;
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

                if (!useVirtualGamepad && (currentController == null || !currentController.isConnected())) {
                    currentController = ExternalController.getController(0);
                    if (currentController != null) {
                        currentController.setTriggerType(triggerType);
                    }
                }

                final boolean enabled = currentController != null || useVirtualGamepad;

                if (enabled && notify) {
                    if (!gamepadClients.contains(port)) gamepadClients.add(port);
                } else {
                    gamepadClients.remove(Integer.valueOf(port));
                }

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD);

                    if (enabled) {
                        sendData.putInt(!useVirtualGamepad ? currentController.getDeviceId() : profile.id);

//                        if (useLegacyInputMethod) {
//                            sendData.put(dinputMapperType);
//                        } else {
                            sendData.put(inputType);
//                        }

                        // Get the original controller name.
                        String originalName = (useVirtualGamepad ? profile.getName() : currentController.getName());
                        byte[] originalBytes = originalName.getBytes();

                        // Calculate the maximum safe length for the name.
                        // Buffer size (64) minus the bytes already written (10) = 54.
                        final int MAX_NAME_LENGTH = 54;

                        byte[] bytesToWrite;

                        // Check if the name is too long and truncate if necessary.
                        if (originalBytes.length > MAX_NAME_LENGTH) {
                            Log.w("WinHandler", "Controller name is too long ("+originalBytes.length+" bytes), truncating: "+originalName);
                            bytesToWrite = new byte[MAX_NAME_LENGTH];
                            System.arraycopy(originalBytes, 0, bytesToWrite, 0, MAX_NAME_LENGTH);
                        } else {
                            bytesToWrite = originalBytes;
                        }

                        // Write the safe, potentially truncated byte array.
                        sendData.putInt(bytesToWrite.length);
                        sendData.put(bytesToWrite);

                    } else {
                        sendData.putInt(0);
                    }

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.GET_GAMEPAD_STATE: {
                if (xinputDisabled) return;
                int gamepadId = receiveData.getInt();
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
                final boolean enabled = currentController != null || useVirtualGamepad;

                if (currentController != null && currentController.getDeviceId() != gamepadId) currentController = null;

                addAction(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    sendData.put((byte)(enabled ? 1 : 0));

                    if (enabled) {
                        sendData.putInt(gamepadId);
                        if (useVirtualGamepad) {
                            profile.getGamepadState().writeTo(sendData);
                        } else {
                            currentController.state.writeTo(sendData);
                        }
                    }

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.RELEASE_GAMEPAD: {
                currentController = null;
                gamepadClients.clear();
                break;
            }
            case RequestCodes.CURSOR_POS_FEEDBACK: {
                short x = receiveData.getShort();
                short y = receiveData.getShort();
                XServer xServer = activity.getXServer();
                xServer.pointer.setX(x);
                xServer.pointer.setY(y);
                activity.getXServerView().requestRender();
                break;
            }
            default: {
                break;
            }
        }
    }



    public void start() {

//        initializeAssignedControllers();

        try {
            // Support remaining UDP implementation
            localhost = InetAddress.getLocalHost();

            // Player 1 (currentController) gets the original non-numbered file
            String p1_mem_path = "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad.mem";
            File p1_memFile = new File(p1_mem_path);
            p1_memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(p1_memFile, "rw")) {
                raf.setLength(64);
                gamepadBuffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                gamepadBuffer.order(ByteOrder.LITTLE_ENDIAN);
                Log.i(TAG, "Successfully created and mapped gamepad file for Player 1");
            }

            // Players 2, 3, 4 (extraControllers) get the numbered files
            for (int i = 0; i < extraGamepadBuffers.length; i++) {
                // The file index is i+1 to create gamepad1.mem, gamepad2.mem, etc.
                String extra_mem_path = "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad" + (i + 1) + ".mem";
                File extra_memFile = new File(extra_mem_path);
                try (RandomAccessFile raf = new RandomAccessFile(extra_memFile, "rw")) {
                    raf.setLength(64);
                    extraGamepadBuffers[i] = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, 64);
                    extraGamepadBuffers[i].order(ByteOrder.LITTLE_ENDIAN);
                    Log.i(TAG, "Successfully created and mapped gamepad file for Player " + (i + 2));
                }
            }
        } catch (IOException e) {
            Log.e("EVSHIM_HOST", "FATAL: Failed to create memory-mapped file(s).", e);
        }


        running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress((InetAddress)null, SERVER_PORT));

                while (running) {
                    socket.receive(receivePacket);

                    synchronized (actions) {
                        receiveData.rewind();
                        byte requestCode = receiveData.get();
                        handleRequest(requestCode, receivePacket.getPort());
                    }
                }
            }
            catch (IOException e) {
                // If the loop was told to stop, this exception is expected.
                if (!running) {
                    Log.d("WinHandler", "Socket closed for shutdown. Listener thread exiting.");
                }
                // Otherwise, it was an unexpected error.
                else {
                    Log.e("WinHandler", "Unexpected socket error", e);
                }
            }
        });

        startRumblePoller();
    }

    private void startRumblePoller() {
        rumblePollerThread = new Thread(() -> {
            while (running) {
                // Get the current profile state on EVERY loop iteration ---
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

                // This condition now uses the fresh, up-to-date 'useVirtualGamepad' variable
                if (gamepadBuffer != null && (currentController != null || useVirtualGamepad)) {
                    // Read the rumble values from the shared memory file.
                    short lowFreq = gamepadBuffer.getShort(32);
                    short highFreq = gamepadBuffer.getShort(34);

                    // Check if the rumble state has changed
                    if (lowFreq != lastLowFreq || highFreq != lastHighFreq) {
                        lastLowFreq = lowFreq;
                        lastHighFreq = highFreq;

                        if (lowFreq == 0 && highFreq == 0) {
                            stopVibration();
                        } else {
                            startVibration(lowFreq, highFreq);
                        }
                    }
                }

                try {
                    Thread.sleep(20); // Poll for new commands 50 times per second
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        rumblePollerThread.start();
    }

    // Handle starting vibration
    private void startVibration(short lowFreq, short highFreq) {
        // Calculate the base amplitude once at the top ---
        int unsignedLowFreq = lowFreq & 0xFFFF;
        int unsignedHighFreq = highFreq & 0xFFFF;
        int dominantRumble = Math.max(unsignedLowFreq, unsignedHighFreq);
        // This is the raw amplitude for a physical X-Input device
        int amplitude = Math.round((float) dominantRumble / 65535.0f * 254.0f) + 1;
        if (amplitude > 255) amplitude = 255;

        // If amplitude is negligible, just stop and exit.
        if (amplitude <= 1) {
            stopVibration();
            return;
        }

        isRumbling = true; // We know we are going to try to rumble.

        // Attempt to vibrate the physical controller first ---
        if (currentController != null) {
            InputDevice device = InputDevice.getDevice(currentController.getDeviceId());
            if (device != null) {
                Vibrator controllerVibrator = device.getVibrator();
                if (controllerVibrator != null && controllerVibrator.hasVibrator()) {
                    // Vibrate the physical controller and then we are done.
                    controllerVibrator.vibrate(VibrationEffect.createOneShot(50, amplitude));
                    return;
                }
            }
        }

        // Fallback to phone vibration if physical controller fails or doesn't exist
        Log.w("WinHandler", "No physical controller vibrator found, falling back to device vibration.");

        Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (phoneVibrator != null && phoneVibrator.hasVibrator()) {
            // --- HAPTIC CURVE LOGIC to make phone vibration feel better ---
            float normalizedAmplitude = (float)amplitude / 255.0f;
            float curvedAmplitude = (float)Math.pow(normalizedAmplitude, 0.6f);
            int finalPhoneAmplitude = (int)(curvedAmplitude * 255);
            if (finalPhoneAmplitude > 255) finalPhoneAmplitude = 255;
            if (finalPhoneAmplitude <= 1) finalPhoneAmplitude = 0;

            if (finalPhoneAmplitude > 0) {
                phoneVibrator.vibrate(VibrationEffect.createOneShot(50, finalPhoneAmplitude));
            }
        }
    }

    //
    // Handle stopping vibration ---
    private void stopVibration() {
        if (!isRumbling) return;

        // Attempt to stop the physical controller's vibration if it exists
        if (currentController != null) {
            InputDevice device = InputDevice.getDevice(currentController.getDeviceId());
            if (device != null) {
                Vibrator vibrator = device.getVibrator();
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.cancel();
                }
            }
        }

        // Always attempt to stop the phone's vibration
        Vibrator phoneVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        if (phoneVibrator != null) {
            phoneVibrator.cancel();
        }

        isRumbling = false;
    }



    public void sendGamepadState() {
        if (!initReceived || gamepadClients.isEmpty() || xinputDisabled ) return; // Add this check
        final ControlsProfile profile = activity.getInputControlsView().getProfile();
        final boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();
        final boolean enabled = currentController != null || useVirtualGamepad;

        for (final int port : gamepadClients) {
            addAction(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                sendData.put((byte)(enabled ? 1 : 0));

                if (enabled) {
                    sendData.putInt(!useVirtualGamepad ? currentController.getDeviceId() : profile.id);
                    GamepadState state = useVirtualGamepad ? profile.getGamepadState() : currentController.state;

                    // Combine gyro input with thumbstick input
                    state.thumbRX = Mathf.clamp(state.thumbRX + gyroX, -1.0f, 1.0f); // Apply clamping
                    state.thumbRY = Mathf.clamp(state.thumbRY + gyroY, -1.0f, 1.0f); // Apply clamping

                    state.writeTo(sendData);
                }

                sendPacket(port);
            });
        }
    }

    public void setXInputDisabled(boolean disabled) {
        this.xinputDisabled = disabled;
        this.xinputDisabledInitialized = true;
        Log.d("WinHandler", "XInput Disabled set to: " + xinputDisabled);
    }



//    public boolean onGenericMotionEvent(MotionEvent event) {
//        boolean handled = false;
//        if (currentController != null && currentController.getDeviceId() == event.getDeviceId()) {
//            handled = currentController.updateStateFromMotionEvent(event);
//            if (handled) sendGamepadState();
//        }
//        return handled;
//    }



    public boolean onGenericMotionEvent(MotionEvent event) {
        int deviceId = event.getDeviceId();
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null || !ControllerManager.isGameController(device)) return false;

        // Ask the ControllerManager which slot this device is assigned to.
        int assignedSlot = controllerManager.getSlotForDevice(deviceId);

        // If the controller is unassigned and we're not already showing a dialog...
        if ((assignedSlot == -1 || !controllerManager.isSlotEnabled(assignedSlot)) && !ignoredDeviceIds.contains(deviceId)) {
            // We only need one dialog at a time, so use the existing isShowingAssignDialog flag
            if (!isShowingAssignDialog) {
                isShowingAssignDialog = true;

                activity.runOnUiThread(() -> {
                    String checkboxMessage = "Don't prompt for this controller again.";

                    ContentDialog.confirmWithCheckbox(activity, "This controller is not active. Open assignment menu?", checkboxMessage, (result) -> {
                        if (result.confirmed) {
                            // User clicked "OK", open the assignment dialog
                            ControllerAssignmentDialog.show(activity);
                        } else {
                            // User clicked "Cancel"
                            if (result.checkboxChecked) {
                                // And they checked the box, so add to the ignore list
                                ignoredDeviceIds.add(deviceId);
                            }
                        }
                        // Allow the prompt to show again for other controllers
                        isShowingAssignDialog = false;
                    });
                });
            }
            return true; // Consume the event
        }

        // --- Player 1 Input Handling ---
        if (assignedSlot == 0) {
            if (currentController == null || currentController.getDeviceId() != deviceId) {
                currentController = ExternalController.getController(deviceId);
            }

            if (currentController != null) {
                boolean handled = currentController.updateStateFromMotionEvent(event);
                if (handled) {
                    sendGamepadState(); // For UDP
                    sendMemoryFileState(); // For SHM
                }

                // Gyro logic for analog triggers
                if (gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_L2 || gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_R2) {
                    float triggerValue = (gyroTriggerButton == KeyEvent.KEYCODE_BUTTON_L2)
                            ? event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                            : event.getAxisValue(MotionEvent.AXIS_RTRIGGER);

                    boolean isPressed = triggerValue > 0.5f;
                    if (isPressed) {
                        if (!isGyroActive) {
                            if (isToggleMode) {
                                isGyroActive = !isGyroActive;
                            } else {
                                isGyroActive = true;
                            }
                        }
                    } else {
                        if (isGyroActive && !isToggleMode) {
                            isGyroActive = false;
                        }
                    }
                }
                return handled;
            }
        }
        // --- Extra Players (P2, P3, P4) Input Handling ---
        else if (assignedSlot > 0) {
            int extraPlayerIndex = assignedSlot - 1; // Convert slot (1-3) to array index (0-2)
            ExternalController extraController = extraControllers[extraPlayerIndex];
            if (extraController == null || extraController.getDeviceId() != deviceId) {
                extraControllers[extraPlayerIndex] = ExternalController.getController(deviceId);
                extraController = extraControllers[extraPlayerIndex];
            }

            if (extraController != null && extraController.updateStateFromMotionEvent(event)) {
                sendMemoryFileState(extraController, extraGamepadBuffers[extraPlayerIndex]);
                return true;
            }
        }

        return false;
    }



    public boolean onKeyEvent(KeyEvent event) {
        int deviceId = event.getDeviceId();
        InputDevice device = event.getDevice();
        if (device == null || !ControllerManager.isGameController(device)) return false;

        int assignedSlot = controllerManager.getSlotForDevice(deviceId);

        // Prompt to assign unassigned controllers to a slot
        if ((assignedSlot == -1 || !controllerManager.isSlotEnabled(assignedSlot)) && !ignoredDeviceIds.contains(deviceId)) {
            // Use the existing isShowingAssignDialog flag
            if (!isShowingAssignDialog) {
                isShowingAssignDialog = true;

                activity.runOnUiThread(() -> {
                    String checkboxMessage = "Don't prompt for this controller again.";

                    ContentDialog.confirmWithCheckbox(activity, "This controller is not active. Open assignment menu?", checkboxMessage, (result) -> {
                        if (result.confirmed) {
                            // User clicked "OK", open the assignment dialog
                            ControllerAssignmentDialog.show(activity);
                        } else {
                            // User clicked "Cancel"
                            if (result.checkboxChecked) {
                                // And they checked the box, so add to the ignore list
                                ignoredDeviceIds.add(deviceId);
                            }
                        }
                        // Allow the prompt to show again for other controllers
                        isShowingAssignDialog = false;
                    });
                });
            }
            return true;
        }

        if (assignedSlot == -1) {
            return false;
        }

        ExternalController controller = null;
        MappedByteBuffer buffer = null;

        if (assignedSlot == 0) {
            controller = currentController;
            buffer = gamepadBuffer;
        } else {
            int extraPlayerIndex = assignedSlot - 1;
            controller = extraControllers[extraPlayerIndex];
            buffer = extraGamepadBuffers[extraPlayerIndex];
        }

        if (controller == null || controller.getDeviceId() != deviceId) {
            controller = ExternalController.getController(deviceId);
            if (assignedSlot == 0) {
                currentController = controller;
                refreshControllerMappings();
            } else {
                extraControllers[assignedSlot - 1] = controller;
            }
        }

        if (controller == null) return false;

        // Process the event
        boolean handled = controller.updateStateFromKeyEvent(event);
        if (handled) {
            // Handle Gyro logic for Player 1 ONLY
            if (assignedSlot == 0 && event.getKeyCode() == gyroTriggerButton && event.getRepeatCount() == 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    isGyroActive = isToggleMode ? !isGyroActive : true;
                } else if (event.getAction() == KeyEvent.ACTION_UP && !isToggleMode) {
                    isGyroActive = false;
                }
            }

            sendMemoryFileState(controller, buffer);
            if (assignedSlot == 0) {
                sendGamepadState();
            }
        }
        return handled;
    }

    public byte getInputType() {
        return inputType;
    }

    public void setInputType(byte inputType) {
        this.inputType = inputType;
    }

    public ExternalController getCurrentController() {
        return currentController;
    }

    public void execWithDelay(String command, int delaySeconds) {
        if (command == null || command.trim().isEmpty() || delaySeconds < 0) return;

        // Use a scheduled executor for delay
        Executors.newSingleThreadScheduledExecutor().schedule(() -> exec(command), delaySeconds, TimeUnit.SECONDS);
    }


    public void refreshControllerMappings() {
        Log.d(TAG, "Refreshing controller assignments from settings...");

        // Clear out all old controller object references to prevent stale states
        currentController = null;
        for (int i = 0; i < extraControllers.length; i++) {
            extraControllers[i] = null;
        }

        // Get a fresh list of physically connected devices
        controllerManager.scanForDevices();

        // Initialize Player 1
        InputDevice p1Device = controllerManager.getAssignedDeviceForSlot(0);
        if (p1Device != null) {
            currentController = ExternalController.getController(p1Device.getId());
            if (currentController != null) {
                currentController.setContext(activity);
                currentController.setTriggerType(triggerType);
                Log.i(TAG, "Initialized Player 1 with: " + p1Device.getName());
            }
        }

        // Initialize Extra Players (2, 3, 4)
        for (int i = 0; i < extraControllers.length; i++) {
            // Player 2 is slot 1, which corresponds to extraControllers[0]
            InputDevice extraDevice = controllerManager.getAssignedDeviceForSlot(i + 1);
            if (extraDevice != null) {
                extraControllers[i] = ExternalController.getController(extraDevice.getId());
                Log.i(TAG, "Initialized Player " + (i + 2) + " with: " + extraDevice.getName());
            }
        }
    }

    private void sendMemoryFileState() {
        sendMemoryFileState(currentController, gamepadBuffer);
    }


    private void sendMemoryFileState(ExternalController controller, MappedByteBuffer buffer) {
        if (buffer == null || controller == null) {
            return;
        }
        GamepadState state = controller.state;
        buffer.clear();

        // --- Sticks and Buttons are perfect. No changes here. ---
        buffer.putShort((short)(state.thumbLX * 32767));
        buffer.putShort((short)(state.thumbLY * 32767));
        buffer.putShort((short)(state.thumbRX * 32767));
        buffer.putShort((short)(state.thumbRY * 32767));

        // Clamp the raw value first – some firmwares report 1.00–1.02 at the top end
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));

        // Optional response curve (keeps a light pull lively, delete if you dislike it)
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);

        // Map 0-1 → -32 768 … 32 767  (never 32 768)
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;  // 0 → -32 767, 1 → 32 767
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;

        buffer.putShort((short)lAxis);
        buffer.putShort((short)rAxis);

        // --- Buttons and D-Pad are perfect. No changes here. ---
        byte[] sdlButtons = new byte[15];
        sdlButtons[0] = state.isPressed(0) ? (byte)1 : (byte)0;  // A
        sdlButtons[1] = state.isPressed(1) ? (byte)1 : (byte)0;  // B
        sdlButtons[2] = state.isPressed(2) ? (byte)1 : (byte)0;  // X
        sdlButtons[3] = state.isPressed(3) ? (byte)1 : (byte)0;  // Y
        sdlButtons[9] = state.isPressed(4) ? (byte)1 : (byte)0;  // Left Bumper
        sdlButtons[10] = state.isPressed(5) ? (byte)1 : (byte)0; // Right Bumper
        sdlButtons[4] = state.isPressed(6) ? (byte)1 : (byte)0;  // Select/Back
        sdlButtons[6] = state.isPressed(7) ? (byte)1 : (byte)0;  // Start
        sdlButtons[7] = state.isPressed(8) ? (byte)1 : (byte)0;  // Left Stick
        sdlButtons[8] = state.isPressed(9) ? (byte)1 : (byte)0;  // Right Stick
        sdlButtons[11] = state.dpad[0] ? (byte)1 : (byte)0;      // DPAD_UP
        sdlButtons[12] = state.dpad[2] ? (byte)1 : (byte)0;      // DPAD_DOWN
        sdlButtons[13] = state.dpad[3] ? (byte)1 : (byte)0;      // DPAD_LEFT
        sdlButtons[14] = state.dpad[1] ? (byte)1 : (byte)0;      // DPAD_RIGHT
        buffer.put(sdlButtons);
        buffer.put((byte)0); // Ignored HAT value
    }

    // In WinHandler.java

    /**
     * Receives the state from the virtual (touchscreen) gamepad and writes it
     * directly to the Player 1 shared memory buffer.
     * @param state The current state of the virtual gamepad.
     */
    public void sendVirtualGamepadState(GamepadState state) {
        if (gamepadBuffer == null || state == null) {
            return;
        }

        // This logic is identical to sendMemoryFileState, but always targets Player 1's buffer.
        gamepadBuffer.clear();

        // Sticks
        gamepadBuffer.putShort((short)(state.thumbLX * 32767));
        gamepadBuffer.putShort((short)(state.thumbLY * 32767));
        gamepadBuffer.putShort((short)(state.thumbRX * 32767));
        gamepadBuffer.putShort((short)(state.thumbRY * 32767));

        // Triggers
        float rawL = Math.max(0f, Math.min(1f, state.triggerL));
        float rawR = Math.max(0f, Math.min(1f, state.triggerR));
        float lCurve = (float)Math.sqrt(rawL);
        float rCurve = (float)Math.sqrt(rawR);
        int lAxis = Math.round(lCurve * 65_534f) - 32_767;
        int rAxis = Math.round(rCurve * 65_534f) - 32_767;
        gamepadBuffer.putShort((short)lAxis);
        gamepadBuffer.putShort((short)rAxis);

        // Buttons & D-Pad
        byte[] sdlButtons = new byte[15];
        sdlButtons[0] = state.isPressed(0) ? (byte)1 : (byte)0;  // A
        sdlButtons[1] = state.isPressed(1) ? (byte)1 : (byte)0;  // B
        sdlButtons[2] = state.isPressed(2) ? (byte)1 : (byte)0;  // X
        sdlButtons[3] = state.isPressed(3) ? (byte)1 : (byte)0;  // Y
        sdlButtons[9] = state.isPressed(4) ? (byte)1 : (byte)0;  // Left Bumper
        sdlButtons[10] = state.isPressed(5) ? (byte)1 : (byte)0; // Right Bumper
        sdlButtons[4] = state.isPressed(6) ? (byte)1 : (byte)0;  // Select/Back
        sdlButtons[6] = state.isPressed(7) ? (byte)1 : (byte)0;  // Start
        sdlButtons[7] = state.isPressed(8) ? (byte)1 : (byte)0;  // Left Stick
        sdlButtons[8] = state.isPressed(9) ? (byte)1 : (byte)0;  // Right Stick
        sdlButtons[11] = state.dpad[0] ? (byte)1 : (byte)0;      // DPAD_UP
        sdlButtons[12] = state.dpad[2] ? (byte)1 : (byte)0;      // DPAD_DOWN
        sdlButtons[13] = state.dpad[3] ? (byte)1 : (byte)0;      // DPAD_LEFT
        sdlButtons[14] = state.dpad[1] ? (byte)1 : (byte)0;      // DPAD_RIGHT

        gamepadBuffer.put(sdlButtons);
        gamepadBuffer.put((byte)0); // Ignored HAT value
    }

//    private void initializeAssignedControllers() {
//        Log.d(TAG, "Initializing controller assignments from saved settings...");
//        for (int i = 0; i < MAX_PLAYERS; i++) {
//            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
//            if (device != null) {
//                ExternalController controller = ExternalController.getController(device.getId());
//                if (i == 0) {
//                    currentController = controller;
//                    Log.d(TAG, "Assigned '" + device.getName() + "' to Player 1 at startup.");
//                } else {
//                    // Remember that extraControllers is 0-indexed for players 2-4
//                    // So Player 2 (slot index 1) goes into extraControllers[0]
//                    extraControllers[i - 1] = controller;
//                    Log.d(TAG, "Assigned '" + device.getName() + "' to Player " + (i + 1) + " at startup.");
//                }
//            }
//        }
//        // This ensures P1-specific settings (like trigger type) are applied from preferences.
//        refreshControllerMappings();
//    }

    public void clearIgnoredDevices() {
        ignoredDeviceIds.clear();
    }

    private boolean isLeftTriggerPressed() {
        return currentController != null && currentController.state.triggerL > 0.5f; // Assuming 0.5f is the threshold for pressed
    }


    public void updateGyroData(float rawGyroX, float rawGyroY) {
        // Check if gyro is enabled before processing the data
        if (!preferences.getBoolean("gyro_enabled", false)) {
            return; // Exit if the gyro is disabled
        }

        boolean shouldProcessGyro = true;

        // Check if processing gyro data only when the left trigger is held
        if (processGyroWithLeftTrigger) {
            shouldProcessGyro = isLeftTriggerPressed();
        }

        if (isGyroActive) {
            // Apply deadzone
            if (Math.abs(rawGyroX) < gyroDeadzone) rawGyroX = 0;
            if (Math.abs(rawGyroY) < gyroDeadzone) rawGyroY = 0;

            // Apply inversion
            if (invertGyroX) rawGyroX = -rawGyroX;
            if (invertGyroY) rawGyroY = -rawGyroY;

            // Further reduce sensitivity by lowering the multiplier
            float sensitivityMultiplier = 0.25f; // Reduce the sensitivity even more
            rawGyroX *= gyroSensitivityX * sensitivityMultiplier;
            rawGyroY *= gyroSensitivityY * sensitivityMultiplier;

            // Apply smoothing
            smoothGyroX = smoothGyroX * smoothingFactor + rawGyroX * (1 - smoothingFactor);
            smoothGyroY = smoothGyroY * smoothingFactor + rawGyroY * (1 - smoothingFactor);

            // Clamp the result to reduce the overall range of movement
            smoothGyroX = Mathf.clamp(smoothGyroX, -0.25f, 0.25f); // Reduce clamping range for less movement
            smoothGyroY = Mathf.clamp(smoothGyroY, -0.25f, 0.25f);

            // Update the gyro data
            this.gyroX = smoothGyroX;
            this.gyroY = smoothGyroY;

            // Send the updated gamepad state
            sendGamepadState();
        }
    }

}
