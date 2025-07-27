package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.graphics.Point;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.MotionControls;

import java.util.List;

public class ControllerAssignmentDialog {
    private final ContentDialog dialog;
    private final ControllerManager controllerManager;

    // We use arrays to easily manage the views for each player row
    private final CheckBox[] checkBoxes = new CheckBox[4];
    private final TextView[] deviceNameTextViews = new TextView[4];
    private final Button[] assignButtons = new Button[4];

    public static void show(Context context) {

        // We now need to know how many players were active at launch
        int initialPlayerCount = ControllerManager.getInstance().getEnabledPlayerCount();
        new ControllerAssignmentDialog(context, initialPlayerCount).showContentDialog();
    }

    private final TextView restartRequiredView; // Add this
    private final int initialPlayerCount;

    private final CheckBox[] vibrateBoxes = new CheckBox[4];
    private final Button[] resetButtons = new Button[4];

    private Button gyroButtonP1;

    private ControllerAssignmentDialog(Context context, int initialPlayerCount) {
        boolean dark = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("dark_mode", false);

        ContextThemeWrapper themed =
                new ContextThemeWrapper(context, dark ? R.style.ContentDialog : R.style.AppTheme);

        // Create the dialog USING the themed context
        this.dialog = new ContentDialog(themed, R.layout.controller_assignment_dialog);
        this.dialog.setTitle(R.string.controller_assignment);


        this.controllerManager = ControllerManager.getInstance();
        this.initialPlayerCount = initialPlayerCount;

        initializeViews();

        // Set after initializeViews so the id exists
        restartRequiredView = dialog.getContentView().findViewById(R.id.TVRestartRequired);

        // If you still want to force text color in dark:
        if (dark) {
            View root = dialog.getContentView();
            if (root instanceof ViewGroup) {
                setTextColorForDialog((ViewGroup) root, 0xFFFFFFFF); // white
            }
        }

        populateView();
        setupListeners();
    }


    private static int dp(Context c, int v){
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    @SuppressWarnings("deprecation")
    public void showContentDialog() {

        dialog.show();
        Window w = dialog.getWindow();
        if (w == null) return;

        int widthPx;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowMetrics metrics = w.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect b = metrics.getBounds();
            widthPx = b.width();                       // use width, not the shorter side
        } else {
            Point p = new Point();
            w.getWindowManager().getDefaultDisplay().getSize(p);
            widthPx = p.x;                             // width
        }

        int capPx = dp(dialog.getContext(), 540);      // was 640dp; bump if you want wider
        int target = Math.min((int) (widthPx * 0.90f), capPx);
        w.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT);
    }


    private void initializeViews() {
        View view = dialog.getContentView();
        // Player 1
        checkBoxes[0] = view.findViewById(R.id.CBPlayer1);
        deviceNameTextViews[0] = view.findViewById(R.id.TVPlayer1DeviceName);
        assignButtons[0] = view.findViewById(R.id.BTNAssignP1);
        vibrateBoxes[0] = view.findViewById(R.id.CBVibrateP1);
        resetButtons[0] = view.findViewById(R.id.BTNResetP1);
        // Player 2
        checkBoxes[1] = view.findViewById(R.id.CBPlayer2);
        deviceNameTextViews[1] = view.findViewById(R.id.TVPlayer2DeviceName);
        assignButtons[1] = view.findViewById(R.id.BTNAssignP2);
        vibrateBoxes[1] = view.findViewById(R.id.CBVibrateP2);
        resetButtons[1] = view.findViewById(R.id.BTNResetP2);
        // Player 3
        checkBoxes[2] = view.findViewById(R.id.CBPlayer3);
        deviceNameTextViews[2] = view.findViewById(R.id.TVPlayer3DeviceName);
        assignButtons[2] = view.findViewById(R.id.BTNAssignP3);
        vibrateBoxes[2] = view.findViewById(R.id.CBVibrateP3);
        resetButtons[2] = view.findViewById(R.id.BTNResetP3);
        // Player 4
        checkBoxes[3] = view.findViewById(R.id.CBPlayer4);
        deviceNameTextViews[3] = view.findViewById(R.id.TVPlayer4DeviceName);
        assignButtons[3] = view.findViewById(R.id.BTNAssignP4);
        vibrateBoxes[3] = view.findViewById(R.id.CBVibrateP4);
        resetButtons[3] = view.findViewById(R.id.BTNResetP4);
    }

    /**
     * Reads the current state from ControllerManager and updates the UI.
     */
    private void populateView() {
        // Rescan for devices every time the dialog is shown to get the latest list
        controllerManager.scanForDevices();

        for (int i = 0; i < 4; i++) {
            checkBoxes[i].setChecked(controllerManager.isSlotEnabled(i));
            if (vibrateBoxes[i] != null) {
                vibrateBoxes[i].setChecked(controllerManager.isVibrationEnabled(i));
            }
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            deviceNameTextViews[i].setText(device != null ? device.getName() : dialog.getContext().getString(R.string.not_assigned));
        }

        for (int i = 0; i < 4; i++) {
            checkBoxes[i].setChecked(controllerManager.isSlotEnabled(i));
            if (vibrateBoxes[i] != null) {
                vibrateBoxes[i].setChecked(controllerManager.isVibrationEnabled(i));
            }
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            deviceNameTextViews[i].setText(
                    device != null ? device.getName() : dialog.getContext().getString(R.string.not_assigned)
            );
            deviceNameTextViews[i].setSelected(true);   // needed for marquee
        }
    }

    /**
     * Sets up the OnClickListeners for all interactive views.
     */
    private void setupListeners() {

        // Set up listeners for all 4 rows
        for (int i = 0; i < 4; i++) {
            final int slotIndex = i;

            // Checkbox listeners (this logic is unchanged and correct)
            checkBoxes[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                controllerManager.setSlotEnabled(slotIndex, isChecked);
                if (!isChecked) {
                    for (int j = slotIndex + 1; j < 4; j++) {
                        if (controllerManager.isSlotEnabled(j))
                            controllerManager.setSlotEnabled(j, false);
                    }
                } else {
                    for (int j = 0; j < slotIndex; j++) {
                        if (!controllerManager.isSlotEnabled(j))
                            controllerManager.setSlotEnabled(j, true);
                    }
                }
                populateView();

                // NOW, check if a restart is needed
                if (controllerManager.getEnabledPlayerCount() != initialPlayerCount) {
                    restartRequiredView.setVisibility(View.VISIBLE);
                } else {
                    restartRequiredView.setVisibility(View.GONE);
                }
            });

            vibrateBoxes[i].setOnCheckedChangeListener((b, checked) ->
                    controllerManager.setVibrationEnabled(slotIndex, checked));

            resetButtons[i].setOnClickListener(v -> {
                controllerManager.unassignSlot(slotIndex);
                populateView();
            });

            // "Assign" button listeners
            assignButtons[i].setOnClickListener(v -> {

                // Prompt the user
                String message = dialog.getContext().getString(R.string.press_any_button_for_player) + " " + (slotIndex + 1);
                dialog.setMessage(message);

                // Tell the ContentDialog to start listening for the next controller input.
                dialog.setOnControllerInputListener(device -> {
                    if (!ControllerManager.isGameController(device)) {
                        return; // ignore mice / touchpads / stylus, even if they claim JOYSTICK
                    }
                    controllerManager.assignDeviceToSlot(slotIndex, device);
                    dialog.setMessage(null);
                    dialog.setOnControllerInputListener(null);
                    populateView();
                });
            });
        }

        dialog.setOnConfirmCallback(() -> controllerManager.saveAssignments());
    }

    private static boolean isPointerLike(InputDevice d) {
        if (d == null) return false;
        int s = d.getSources();
        return ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                || ((s & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE)
                || ((s & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
                || ((s & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS)
                // Be conservative: any generic pointer without gamepad/joystick bits.
                || (((s & InputDevice.SOURCE_CLASS_POINTER) == InputDevice.SOURCE_CLASS_POINTER)
                && ((s & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) == 0));
    }

    private void setTextColorForDialog(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                // If the child is a ViewGroup, recursively apply the color
                setTextColorForDialog((ViewGroup) child, color);
            } else if (child instanceof TextView) {
                // If the child is a TextView, set its text color
                ((TextView) child).setTextColor(color);
            }
        }
    }
}