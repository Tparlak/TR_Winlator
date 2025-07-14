package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.ControllerManager;

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
        new ControllerAssignmentDialog(context, initialPlayerCount).show();
    }

    private final TextView restartRequiredView; // Add this
    private final int initialPlayerCount;

    private ControllerAssignmentDialog(Context context, int initialPlayerCount) {
        this.dialog = new ContentDialog(context, R.layout.controller_assignment_dialog);
        this.dialog.setTitle(R.string.controller_assignment);
        this.controllerManager = ControllerManager.getInstance();
        this.initialPlayerCount = initialPlayerCount; // Store the initial count

        initializeViews();
        // Find the new TextView
        restartRequiredView = dialog.getContentView().findViewById(R.id.TVRestartRequired);

        populateView();
        setupListeners();
    }

    private void show() {
        dialog.show();
    }

    private void initializeViews() {
        View view = dialog.getContentView();
        // Player 1
        checkBoxes[0] = view.findViewById(R.id.CBPlayer1);
        deviceNameTextViews[0] = view.findViewById(R.id.TVPlayer1DeviceName);
        assignButtons[0] = view.findViewById(R.id.BTNAssignP1);
        // Player 2
        checkBoxes[1] = view.findViewById(R.id.CBPlayer2);
        deviceNameTextViews[1] = view.findViewById(R.id.TVPlayer2DeviceName);
        assignButtons[1] = view.findViewById(R.id.BTNAssignP2);
        // Player 3
        checkBoxes[2] = view.findViewById(R.id.CBPlayer3);
        deviceNameTextViews[2] = view.findViewById(R.id.TVPlayer3DeviceName);
        assignButtons[2] = view.findViewById(R.id.BTNAssignP3);
        // Player 4
        checkBoxes[3] = view.findViewById(R.id.CBPlayer4);
        deviceNameTextViews[3] = view.findViewById(R.id.TVPlayer4DeviceName);
        assignButtons[3] = view.findViewById(R.id.BTNAssignP4);
    }

    /**
     * Reads the current state from ControllerManager and updates the UI.
     */
    private void populateView() {
        // Rescan for devices every time the dialog is shown to get the latest list
        controllerManager.scanForDevices();

        for (int i = 0; i < 4; i++) {
            // Set the checkbox state
            checkBoxes[i].setChecked(controllerManager.isSlotEnabled(i));

            // Find the device assigned to this slot
            InputDevice device = controllerManager.getAssignedDeviceForSlot(i);
            if (device != null) {
                deviceNameTextViews[i].setText(device.getName());
            } else {
                deviceNameTextViews[i].setText(R.string.not_assigned);
            }
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

                populateView(); // Refresh the UI first

                // NOW, check if a restart is needed
                if (controllerManager.getEnabledPlayerCount() != initialPlayerCount) {
                    restartRequiredView.setVisibility(View.VISIBLE);
                } else {
                    restartRequiredView.setVisibility(View.GONE);
                }
            });

            // "Assign" button listeners
            assignButtons[i].setOnClickListener(v -> {
                // Prompt the user
                String message = dialog.getContext().getString(R.string.press_any_button_for_player) + " " + (slotIndex + 1);
                dialog.setMessage(message);

                // Tell the ContentDialog to start listening for the next controller input.
                dialog.setOnControllerInputListener(device -> {
                    // When a controller event is received, this callback fires.
                    controllerManager.assignDeviceToSlot(slotIndex, device);

                    // Clean up and exit listening mode
                    dialog.setMessage(null);
                    dialog.setOnControllerInputListener(null);
                    populateView(); // Refresh the UI to show the new assignment
                });
            });
        }

        dialog.setOnConfirmCallback(() -> controllerManager.saveAssignments());
    }
}