/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package com.langmuirsystems.firecontrol.fxcontrollers;

import com.langmuirsystems.firecontrol.FireControl;
import com.langmuirsystems.firecontrol.FireControlDialogs;
import com.willwinder.universalgcodesender.connection.ConnectionFactory;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.ComboBox;

import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.listeners.MessageListener;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.utils.Settings;
import com.willwinder.universalgcodesender.utils.SettingsFactory;
import java.io.IOException;
import java.util.List;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * FXML Controller class
 * Controls the main window of the application
 *
 * @author Rostand
 */
public class MainViewController implements Initializable, MessageListener {
   
    @FXML ComboBox<String> portSelectionCombobox;
    @FXML ComboBox<Integer> baudRateSelectionCombobox;
    @FXML BorderPane mainPane;
    @FXML Button connectButton;
    
    private Stage primaryStage;
    
    // CORE UGS Variables
    private BackendAPI backend;
    private Settings settings;
    private BooleanProperty portConnected=new SimpleBooleanProperty(false);
    private ConsoleController console;
    private final String firmware="GRBL";
    
    /**
     * Initializes the controller class.
     * @param url
     * @param rb
     */
    @Override
    public void initialize(URL url, ResourceBundle rb) {
        baudRateSelectionCombobox.getItems().addAll(2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400);
        baudRateSelectionCombobox.setValue(115200);
        
        portConnected.addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean connected) {
                connectButton.setText(connected?"Disconnect":"Connect");
            }
        });
        
        this.settings = SettingsFactory.loadSettings();
        
        FXMLLoader loader= new FXMLLoader();
        
        loader.setLocation(FireControl.class.getResource("fxml/ConsoleView.fxml"));
        try{
            Node consoleNode = loader.load();
            console= loader.getController();
            
            mainPane.setCenter(consoleNode);
        }
        catch(IOException ex){
            FireControlDialogs.showExceptionDialog("fatal error", "couln't load console UI", ex);
        }
    }  
    
    
    public void setBackendAPI(BackendAPI back) throws Exception{
        
        this.backend= back;
        this.backend.addMessageListener(this);
        backend.applySettings(settings);
        
        if(console!=null){
            console.setBackend(back);
        }
    }
    
    
    
    @FXML private void handleConnectionButton(){
        
        if(portConnected.get()) { // the port is connected, and the user wants to disconnect
            try {
                this.backend.disconnect();
            } catch (Exception e) {
                displayErrorDialog(e.getMessage());
            }
            portConnected.set(false);
            
        } else {
            this.console.clear();
            portConnected.set(true);

            String port = portSelectionCombobox.getValue();
            int baudRate = baudRateSelectionCombobox.getValue();
            
            try {
                this.backend.connect(firmware, port, baudRate);
                
                // Let the command field grab focus.
                console.grabFocus();
            } catch (Exception e) {
                displayErrorDialog(e.getMessage());
            }
        }
    }
    
    @FXML private void updateAvailablePorts() {
        portSelectionCombobox.getItems().clear();

        List<String> portList = ConnectionFactory.getPortNames(backend.getSettings().getConnectionDriver());
        if (portList.size() < 1) {
            displayErrorDialog("No serial port found");
        } else {
            portSelectionCombobox.getItems().addAll(portList);
            portSelectionCombobox.setValue(portList.get(0));
        }
    }
    
    private void displayErrorDialog(String msg){
        
        Alert alert= new Alert(Alert.AlertType.ERROR);
        
        alert.setTitle("Error");
        alert.setContentText(msg);
        
        alert.showAndWait();
    }

    @Override
    public void onMessage(MessageType messageType, String message) {
        if(messageType== MessageType.INFO)
            console.write(message);
    }

}
