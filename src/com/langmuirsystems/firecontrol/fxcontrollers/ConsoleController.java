package com.langmuirsystems.firecontrol.fxcontrollers;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import com.langmuirsystems.firecontrol.FireControlDialogs;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UGSEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.apache.commons.lang3.StringUtils;

/**
 * FXML Controller class
 * Provides a console-like GUI text area
 *
 * @author rostand
 */
public class ConsoleController implements Initializable,  UGSEventListener {

    @FXML TextArea consoleTextArea;
    @FXML TextField commadTextField;
    
    private BackendAPI backend;
    private final List<String> commandHistory = new ArrayList<>();
    private int commandNum = -1;
    
    /**
     * Initializes the controller class.
     * @param url
     * @param rb
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        
        commadTextField.setOnKeyPressed((KeyEvent event) -> {
            handleCommandTextFieldKeyTyped(event);
        });
        
    }  
    
    public void setBackend(BackendAPI back){
        this.backend = back;
        if (backend != null) {
            this.backend.addUGSEventListener(this);
            commadTextField.setDisable(!backend.isConnected());
        }
    }
    
    @FXML public void clear(){
        consoleTextArea.clear();
    }
    
    public void grabFocus(){
        // TODO set the focus to console text area
    }
    
    public void write(String message){
        Platform.runLater(()->{consoleTextArea.appendText("\n"+message);});
    }
    
    
    private boolean isArrowKey(KeyEvent e){
        return e.getCode().equals(KeyCode.KP_UP) || e.getCode().equals(KeyCode.KP_DOWN);
    }
    
    /**
     * Action to process when user types key up, or key down or enter key into the command text field
     * @param event 
     */
    private void handleCommandTextFieldKeyTyped(KeyEvent event){
        System.out.println("Enter Key typed : "+(event.getCode().equals((KeyCode.ENTER))));
        if(event.getCode().equals(KeyCode.ENTER)){
            final String str = commadTextField.getText().replaceAll("(\\r\\n|\\n\\r|\\r|\\n)", "");
            if (!StringUtils.isEmpty(str)) {
                try {
                    backend.sendGcodeCommand(str);
                } catch (Exception ex) {
                    FireControlDialogs.showExceptionDialog("error", "An error occured during command execution", ex);
                }

                commadTextField.setText("");
                this.commandHistory.add(str);
                this.commandNum = -1;
            }
        }
        else if (commandHistory.size() > 0 && isArrowKey(event)) {
            boolean pressed = false;
            
            if (event.getCode()== KeyCode.KP_UP) {
                pressed = true;
                commandNum++;
            }
            else if (event.getCode()== KeyCode.KP_DOWN) {
                pressed = true;
                commandNum--;
            }

            if (pressed) {
                if (commandNum < 0) {
                    commandNum = -1;
                    commadTextField.setText("");
                    java.awt.Toolkit.getDefaultToolkit().beep();
                } else if (commandNum > (commandHistory.size()-1)) {
                    commandNum = commandHistory.size()-1;
                    java.awt.Toolkit.getDefaultToolkit().beep();
                }

                // Get index from end.
                int index = commandHistory.size() - 1 - commandNum;
                String text = this.commandHistory.get(index);
                commadTextField.setText(text);
            }
        }        
    }
    
    /**
     * Detect connection events to enable/disable command text area.
     * @param evt
     */
    @Override
    public void UGSEvent(UGSEvent evt) {
        if (evt.isStateChangeEvent()) {
            commadTextField.setDisable(!backend.isIdle());
        }
    }
    
}
