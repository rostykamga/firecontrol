/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.langmuirsystems.firecontrol.ugsproxy;

import com.google.common.io.Files;
import com.langmuirsystems.firecontrol.FireControlDialogs;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.Utils;
import com.willwinder.universalgcodesender.connection.ConnectionFactory;
import com.willwinder.universalgcodesender.firmware.FirmwareSetting;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettingsListener;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.GcodeStats;
import com.willwinder.universalgcodesender.gcode.processors.CommandLengthProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommandProcessor;
import com.willwinder.universalgcodesender.gcode.processors.CommentProcessor;
import com.willwinder.universalgcodesender.gcode.processors.DecimalProcessor;
import com.willwinder.universalgcodesender.gcode.processors.M30Processor;
import com.willwinder.universalgcodesender.gcode.processors.WhitespaceProcessor;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerListener;
import com.willwinder.universalgcodesender.listeners.ControllerStateListener;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.MessageListener;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.Alarm;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.GUIBackend;
import com.willwinder.universalgcodesender.model.Overrides;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.pendantui.SystemStateBean;
import com.willwinder.universalgcodesender.services.MessageService;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.FirmwareUtils;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.SettingChangeListener;
import com.willwinder.universalgcodesender.utils.Settings;
import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author rostand
 */
public class FXBackend implements BackendAPI, ControllerListener, SettingChangeListener, IFirmwareSettingsListener, MessageListener {
    
    private static final Logger logger = Logger.getLogger(FXBackend.class.getName());
    private static final String NEW_LINE = "\n    ";

    private final MessageService messageService = new MessageService();

    private IController controller = null;
    private Settings settings = null;
    private Position machineCoord = null;
    private Position workCoord = null;

    private final Collection<ControllerListener> controllerListeners = new ArrayList<>();
    private final Collection<UGSEventListener> ugsEventListener = new ArrayList<>();
    private final Collection<ControllerStateListener> controllerStateListener = new ArrayList<>();

    // GUI State
    private File gcodeFile = null;
    private File processedGcodeFile = null;
    private File tempDir = null;
    private String lastComment;
    private String activeState;
    private long estimatedSendDuration = -1L;
    private String firmware = null;

    private long lastResponse = Long.MIN_VALUE;
    private boolean streamFailed = false;
    private boolean autoconnect = false;
    private final Timer autoConnectTimer = new Timer("AutoConnectTimer", true);
    
    private GcodeParser gcp = new GcodeParser();
    
     public FXBackend() {
        scheduleTimers();
        messageService.addListener(this);
    }

    private void scheduleTimers() {
        autoConnectTimer.scheduleAtFixedRate(new TimerTask() {
            private int count = 0;
            @Override
            public void run() {
                //autoconnect();

                // Move the mouse every 30 seconds to prevent sleeping.
                if (isPaused() || isActive()) {
                    count++;
                    if (count % 10 == 0) {
                        keepAwake();
                        count = 0;
                    }
                }
            }
        }, 1000, 1000);
    }

    @Override
    public void addUGSEventListener(UGSEventListener listener) {
        if (!ugsEventListener.contains(listener)) {
            logger.log(Level.INFO, "Adding UGSEvent listener: {0}", listener.getClass().getSimpleName());
            ugsEventListener.add(listener);
        }
    }

    @Override
    public void removeUGSEventListener(UGSEventListener listener) {
        if (ugsEventListener.contains(listener)) {
            logger.log(Level.INFO, "Removing UGSEvent listener: {0}", listener.getClass().getSimpleName());
            ugsEventListener.remove(listener);
        }
    }

    @Override
    public void addControllerStateListener(ControllerStateListener listener) {
        if (!controllerStateListener.contains(listener)) {
            logger.log(Level.INFO, "Adding controller state listener: {0}", listener.getClass().getSimpleName());
            controllerStateListener.add(listener);
        }
    }

    @Override
    public void removeControllerStateListener(ControllerStateListener listener) {
        if (controllerStateListener.contains(listener)) {
            logger.log(Level.INFO, "Removing controller state listener: {0}", listener.getClass().getSimpleName());
            controllerStateListener.remove(listener);
        }
    }

    @Override
    public void addControllerListener(ControllerListener listener) {
        if (!controllerListeners.contains(listener)) {
            logger.log(Level.INFO, "Adding controller listener: {0}", listener.getClass().getSimpleName());
            controllerListeners.add(listener);
        }

        if (this.controller != null) {
            this.controller.addListener(listener);
        }
    }

    @Override
    public void removeControllerListener(ControllerListener listener) {
        if (controllerListeners.contains(listener)) {
            logger.log(Level.INFO, "Removing controller state listener: {0}", listener.getClass().getSimpleName());
            controllerListeners.remove(listener);
        }

        if (this.controller != null) {
            this.controller.removeListener(listener);
        }
    }

    @Override
    public void addMessageListener(MessageListener listener) {
        this.messageService.addListener(listener);
    }

    @Override
    public void removeMessageListener(MessageListener listener) {
        this.messageService.removeListener(listener);
    }

    //////////////////
    // GUI API
    //////////////////

    @Override
    public void preprocessAndExportToFile(File f) throws Exception {
        preprocessAndExportToFile(this.gcp, this.getGcodeFile(), f);
    }
    
    /**
     * Special utility to loop over a gcode file and apply any modifications made by a gcode parser. The results are
     * stored in a GcodeStream formatted file.
     * Additional rules:
     * * Comment lines are left
     * @param gcp
     * @param input
     * @param output
     * @throws java.lang.Exception
     */
    protected void preprocessAndExportToFile(GcodeParser gcp, File input, File output) throws Exception {
        logger.log(Level.INFO, "Preprocessing {0} to {1}", new Object[]{input.getCanonicalPath(), output.getCanonicalPath()});
        GcodeParserUtils.processAndExport(gcp, input, output);
    }

    private void initGcodeParser() {
        // Configure gcode parser.
        gcp.resetCommandProcessors();

        try {
            List<CommandProcessor> processors = FirmwareUtils.getParserFor(firmware, settings).orElse(null);
            processors.forEach((p) -> {
                gcp.addCommandProcessor(p);
            });
        }
        catch (Exception e) {
            initializeWithFallbackProcessors(gcp);
        }
    }

    private void updateWithFirmware(String firmware) throws Exception {
        this.firmware = firmware;

        // Load command processors for this firmware.
        try {
            FirmwareUtils.getParserFor(firmware, settings);
        } catch (Exception e) {
            disconnect();
            throw new Exception("Bad configuration file for: " + firmware + " (" + e.getMessage() + ")");
        }

        // Reload gcode file to use the controllers processors.
        if (this.gcodeFile != null) {
            setGcodeFile(this.gcodeFile);
        }
    }

    @Override
    public void connect(String firmware, String port, int baudRate) throws Exception {
        logger.log(Level.INFO, "Connecting to {0} on port {1}", new Object[]{firmware, port});
        updateWithFirmware(firmware);

        this.controller = fetchControllerFromFirmware(firmware);
        this.controller.setMessageService(messageService);
        applySettings(settings);

        this.controller.addListener(this);
        for (ControllerListener l : controllerListeners) {
            this.controller.addListener(l);
        }

        this.controller.getFirmwareSettings().addListener(this);

        if (openCommConnection(port, baudRate)) {
            streamFailed = false;   //reset
        }
    }

    protected IController fetchControllerFromFirmware(String firmware) throws Exception {
        Optional<IController> c = FirmwareUtils.getControllerFor(firmware);
        if (!c.isPresent()) {
            throw new Exception("Unable to create handler for: " + firmware);
        }
        return c.get();
    }

    @Override
    public boolean isConnected() {
        boolean isConnected = this.controller != null && this.controller.isCommOpen();
        logger.log(Level.FINEST, "Is connected: {0}", isConnected);
        return isConnected;
    }
    
    @Override
    public void disconnect() throws Exception {
        autoconnect = false;
        disconnectInternal();
    }

    private void disconnectInternal() throws Exception {
        logger.log(Level.INFO, "Disconnecting.");
        if (this.controller != null) {
            this.controller.closeCommPort();
            this.controller.removeListener(this);
            this.controller.getFirmwareSettings().removeListener(this);
            this.controller = null;
            this.sendUGSEvent(new UGSEvent(UGSEvent.ControlState.COMM_DISCONNECTED), false);
        }
    }

    public void autoconnect() {
        if (!autoconnect) {
            return;
        }

        if (!isConnected()) {
            if (settings == null || streamFailed) {
                return;
            }
            if (lastResponse == Long.MIN_VALUE && autoconnect) {
                logger.log(Level.INFO, "Attempting auto connect.");
            } else if (lastResponse > Long.MIN_VALUE && settings.isAutoReconnect()) {
                logger.log(Level.INFO, "Attempting auto reconnect.");
            } else {
                return;
            }

            try {
                List<String> portList = ConnectionFactory.getPortNames(getSettings().getConnectionDriver());
                boolean portMatch = false;
                for (String port : portList) {
                    if (port.equals(settings.getPort())) {
                        portMatch = true;
                        break;
                    }
                }

                if (portMatch) {
                    connect(settings.getFirmwareVersion(), settings.getPort(), Integer.parseInt(settings.getPortRate()));
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Auto connect failed",e);
            }
        }
    }

    private void keepAwake() {
        logger.log(Level.INFO, "Moving the mouse location slightly to keep the computer awake.");
        try {
            Robot hal = new Robot();
            Point pObj = MouseInfo.getPointerInfo().getLocation();
            hal.mouseMove(pObj.x + 1, pObj.y + 1);
            hal.mouseMove(pObj.x - 1, pObj.y - 1);
            pObj = MouseInfo.getPointerInfo().getLocation();
            logger.log(Level.INFO, "{0}x>>{1}  y>>{2}", new Object[]{pObj.toString(), pObj.x, pObj.y});
        } catch (AWTException | NullPointerException ex) {
            Logger.getLogger(GUIBackend.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void applySettings(Settings settings) throws Exception {
        logger.log(Level.INFO, "Applying settings.");
        this.settings = settings;
        this.settings.setSettingChangeListener(this);
        if (this.controller != null) {
            applySettingsToController(this.settings, this.controller);
        }
        // Reload gcode file to use the controllers processors.
        if (this.gcodeFile != null) {
            setGcodeFile(this.gcodeFile);
        }
    }

    /**
     * This allows us to visualize a file without loading a controller profile.
     */
    private static void initializeWithFallbackProcessors(GcodeParser parser) {
        // Comment processor must come first otherwise we try to parse codes
        // out of the comments, like an f-code when we see "(feed rate is 100)"
        parser.addCommandProcessor(new CommentProcessor());
        parser.addCommandProcessor(new WhitespaceProcessor());
        parser.addCommandProcessor(new M30Processor());
        parser.addCommandProcessor(new DecimalProcessor(4));
        parser.addCommandProcessor(new CommandLengthProcessor(50));
    }

    @Override
    public void updateSystemState(SystemStateBean systemStateBean) {
        logger.log(Level.FINE, "Getting system state 'updateSystemState'");
        if (gcodeFile != null)
            systemStateBean.setFileName(gcodeFile.getAbsolutePath());
        systemStateBean.setLatestComment(lastComment);
        systemStateBean.setActiveState(activeState);

        systemStateBean.setControlState(getControlState());
        if (this.machineCoord != null) {
            systemStateBean.setMachineX(Utils.formatter.format(this.machineCoord.x));
            systemStateBean.setMachineY(Utils.formatter.format(this.machineCoord.y));
            systemStateBean.setMachineZ(Utils.formatter.format(this.machineCoord.z));
        }
        if (this.controller != null) {
            systemStateBean.setRemainingRows(String.valueOf(this.getNumRemainingRows()));
            systemStateBean.setRowsInFile(String.valueOf(this.getNumRows()));
            systemStateBean.setSentRows(String.valueOf(this.getNumSentRows()));
            systemStateBean.setDuration(String.valueOf(this.getSendDuration()));
            systemStateBean.setEstimatedTimeRemaining(String.valueOf(this.getSendRemainingDuration()));
        }
        if (this.workCoord != null) {
            systemStateBean.setWorkX(Utils.formatter.format(this.workCoord.x));
            systemStateBean.setWorkY(Utils.formatter.format(this.workCoord.y));
            systemStateBean.setWorkZ(Utils.formatter.format(this.workCoord.z));
        }
        systemStateBean.setSendButtonEnabled(this.canSend());
        systemStateBean.setPauseResumeButtonEnabled(this.canPause());
        systemStateBean.setCancelButtonEnabled(this.canCancel());
    }

    @Override
    public void sendGcodeCommand(String commandText) throws Exception {
        sendGcodeCommand(false, commandText);
    }

    @Override
    public void sendGcodeCommand(boolean restoreParserState, String commandText) throws Exception {
        if (this.isConnected()) {
            GcodeCommand command = controller.createCommand(commandText);
            command.setTemporaryParserModalChange(restoreParserState);
            sendGcodeCommand(command);

            if (restoreParserState && this.isConnected()) {
                controller.restoreParserModalState();
            }
        } else {
            throw new Exception(Localization.getString("controller.log.notconnected"));
        }
    }

    @Override
    public void sendGcodeCommand(GcodeCommand command) throws Exception {
        if (this.isConnected()) {
            logger.log(Level.INFO, "Sending gcode command: {0}", command.getCommandString());
            controller.sendCommandImmediately(command);
        }
    }

    /**
     * Sends a G91 command in some combination of x, y, and z directions with a
     * step size of stepDirection.
     * 
     * Direction is specified by the direction param being positive or negative.
     * @param dirX
     * @param dirY
     * @param dirZ
     * @param stepSize
     * @param feedRate
     * @param units
     * @throws java.lang.Exception
     */
    @Override
    public void adjustManualLocation(int dirX, int dirY, int dirZ,
            double stepSize, double feedRate, UnitUtils.Units units) throws Exception {
        // Don't send empty commands.
        if ((dirX == 0) && (dirY == 0) && (dirZ == 0)) {
            return;
        }

        controller.jogMachine(dirX, dirY, dirZ, stepSize, feedRate, units);
    }

    @Override
    public void probe(String axis, double feedRate, double distance, UnitUtils.Units units) throws Exception {
        controller.probe(axis, feedRate, distance, units);
    }

    @Override
    public void offsetTool(String axis, double offset, UnitUtils.Units units) throws Exception {
        controller.offsetTool(axis, offset, units);
    }

    @Override
    public Settings getSettings() {
        logger.log(Level.FINEST, "Getting settings.");
        return this.settings;
    }

    @Override
    public UGSEvent.ControlState getControlState() {
        logger.log(Level.FINEST, "Getting control state.");
        return this.controller == null ?
                UGSEvent.ControlState.COMM_DISCONNECTED : this.controller.getControlState();
    }

    @Override
    public Position getWorkPosition() {
        return this.workCoord;
    }

    @Override
    public Position getMachinePosition() {
        return this.machineCoord;
    }

    @Override
    public GcodeState getGcodeState() {
        if (this.controller != null) {
          return this.controller.getCurrentGcodeState();
        }
        return null;
    }
    
    @Override
    public IController getController() {
        logger.log(Level.FINEST, "Getting controller");
        return this.controller;
    }

    private File getTempDir() {
        if (tempDir == null) {
            tempDir = Files.createTempDir();
        }
        return tempDir;
    }

    @Override
    public void setGcodeFile(File file) throws Exception {
        logger.log(Level.INFO, "Setting gcode file.");
        initGcodeParser();
        this.gcodeFile = file;
        this.processedGcodeFile = null;

        this.sendUGSEvent(new UGSEvent(UGSEvent.FileState.FILE_LOADING,
                file.getAbsolutePath()), false);

        initializeProcessedLines(true, this.gcodeFile, this.gcp);

        this.sendUGSEvent(new UGSEvent(UGSEvent.FileState.FILE_LOADED,
                processedGcodeFile.getAbsolutePath()), false);
    }

    @Override
    public void applyGcodeParser(GcodeParser parser) throws Exception {
        logger.log(Level.INFO, "Applying new parser filters.");

        if (this.processedGcodeFile == null) {
            return;
        }

        // re-initialize starting with the already processed file.
        initializeProcessedLines(true, this.processedGcodeFile, parser);

        this.sendUGSEvent(new UGSEvent(UGSEvent.FileState.FILE_LOADED,
                processedGcodeFile.getAbsolutePath()), false);
    }
    
    @Override
    public File getGcodeFile() {
        logger.log(Level.FINEST, "Getting gcode file.");
        return this.gcodeFile;
    }

    @Override
    public File getProcessedGcodeFile() {
        logger.log(Level.INFO, "Getting processed gcode file.");
        return this.processedGcodeFile;
    }
    
    @Override
    public void send() throws Exception {
        logger.log(Level.INFO, "Sending gcode file.");
        // Note: there is a divide by zero error in the timer because it uses
        //       the rowsValueLabel that was just reset.

        try {
            // This will throw an exception and prevent that other stuff from
            // happening (clearing the table before its ready for clearing.
            this.controller.isReadyToStreamFile();

            //this.controller.queueCommands(processedCommandLines);
            //this.controller.queueStream(new BufferedReader(new FileReader(this.processedGcodeFile)));
            this.controller.queueStream(new GcodeStreamReader(this.processedGcodeFile));

            this.controller.beginStreaming();
        } catch (Exception e) {
            this.sendUGSEvent(new UGSEvent(UGSEvent.ControlState.COMM_IDLE), false);
            e.printStackTrace();
            throw new Exception(Localization.getString("mainWindow.error.startingStream") + ": "+e.getMessage());
        }
    }
    
    @Override
    public long getNumRows() {
        logger.log(Level.FINEST, "Getting number of rows.");
        return this.controller.rowsInSend();
    }
    
    @Override
    public long getNumSentRows() {
        logger.log(Level.FINEST, "Getting number of sent rows.");
        return controller == null ? 0 : controller.rowsSent();
    }

    @Override
    public long getNumCompletedRows() {
        logger.log(Level.FINEST, "Getting number of completed rows.");
        return controller == null ? 0 : controller.rowsCompleted();
    }

    @Override
    public long getNumRemainingRows() {
        return controller == null ? 0 : controller.rowsRemaining();
    }

    @Override
    public long getSendDuration() {
        return controller == null ? 0 : controller.getSendDuration();
    }
    
    @Override
    public long getSendRemainingDuration() {
        long sent = this.getNumCompletedRows();

        // Early exit condition. Can't make an estimate if we haven't started.
        if (sent == 0) { return -1L; }

        long estimate = this.estimatedSendDuration;
        
        long elapsedTime = this.getSendDuration();
        // If we don't have an actual duration estimate, make a crude estimate.
        if (estimate <= 0) {
            long timePerCode = elapsedTime / sent;
            estimate = timePerCode * this.getNumRows();
        }
        
        return estimate - elapsedTime;
    }

    @Override
    public void pauseResume() throws Exception {
        logger.log(Level.INFO, "Pause/Resume");
        try {
            switch(getControlState()) {
                case COMM_IDLE:
                default:
                    if (!isSendingFile()) {
                        throw new Exception("Cannot pause while '" + getControlState() + "'.");
                    }
                    // Fall through if we're really sending a file.
                    // This can happen at the beginning of a stream when GRBL
                    // reports an error before we send it a status request.
                case COMM_SENDING:
                    this.controller.pauseStreaming();
                    return;
                case COMM_SENDING_PAUSED:
                    this.controller.resumeStreaming();
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception in pauseResume", e);
            throw new Exception(Localization.getString("mainWindow.error.pauseResume"));
        }
    }
    
    @Override
    public String getPauseResumeText() {
        if (isPaused())
            return Localization.getString("mainWindow.ui.resumeButton");
        else
            return Localization.getString("mainWindow.ui.pauseButton");
    }
    
    @Override
    public boolean isActive() {
        return this.controller != null && !isIdle();
    }

    @Override
    public boolean isSendingFile() {
        return this.controller != null && this.controller.isStreaming();
    }

    @Override
    public boolean isIdle() {
        return this.controller != null && controller.isIdle();
    }
    
    @Override
    public boolean isPaused() {
        return this.controller != null && this.controller.isPaused();
    }
    
    @Override
    public boolean canPause() {
        return this.controller != null && isConnected() && !isIdle() && !isPaused();
    }

    @Override
    public boolean canCancel() {
        return canPause() || isPaused();
    }
    
    @Override
    public boolean canSend() {
        return isIdle() && (this.gcodeFile != null);
    }
    
    @Override
    public void cancel() throws Exception {
        if (this.canCancel()) {
            this.controller.cancelSend();
        }
    }

    @Override
    public void returnToZero() throws Exception {
        this.controller.returnToHome();
    }

    @Override
    public void resetCoordinatesToZero() throws Exception {
        this.controller.resetCoordinatesToZero();
    }

    @Override
    public void resetCoordinateToZero(Axis coordinate) throws Exception {
        this.controller.resetCoordinateToZero(coordinate);
    }
    
    @Override
    public void killAlarmLock() throws Exception {
        this.controller.killAlarmLock();
    }
    
    @Override
    public void performHomingCycle() throws Exception {
        this.controller.performHomingCycle();
    }
    
    @Override
    public void toggleCheckMode() throws Exception {
        this.controller.toggleCheckMode();
    }
    
    @Override
    public void issueSoftReset() throws Exception {
        this.controller.issueSoftReset();
    }
    
    @Override
    public void requestParserState() throws Exception {
        this.controller.viewParserState();
    }

    //////////////////
    // Controller Listener
    //////////////////
    @Override
    public void controlStateChange(UGSEvent.ControlState state) {
        // This comes from the boss, force the event change.
        this.sendUGSEvent(new UGSEvent(state), true);
    }

    @Override
    public void fileStreamComplete(String filename, boolean success) {
        this.sendUGSEvent(new UGSEvent(UGSEvent.FileState.FILE_STREAM_COMPLETE, filename), false);
    }

    @Override
    public void receivedAlarm(Alarm alarm) {
        this.sendUGSEvent(new UGSEvent(alarm), true);
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
    }

    @Override
    public void commandSent(GcodeCommand command) {
    }

    @Override
    public void commandComplete(GcodeCommand command) {
    }

    @Override
    public void commandComment(String comment) {
        this.lastComment = comment;
    }

    @Override
    public void probeCoordinates(Position p) {
        this.sendUGSEvent(new UGSEvent(p), false);
    }

    @Override
    public void statusStringListener(ControllerStatus status) {
        this.activeState = status.getStateString();
        this.machineCoord = status.getMachineCoord();
        this.workCoord = status.getWorkCoord();
        this.lastResponse = System.currentTimeMillis();
        this.sendControllerStateEvent(new UGSEvent(status));
    }

    @Override
    public void postProcessData(int numRows) {
    }
    
    ////////////////////
    // Utility functions
    ////////////////////
    
    /**
     * This would be static but I want to define it in the interface.
     * @param settings Settings to apply to the controller.
     * @param controller Controller to receive settings.
     * @throws java.lang.Exception Exception thrown if controller doesn't support some settings.
     */
    @Override
    public void applySettingsToController(Settings settings, IController controller) throws Exception {
        if (settings == null) {
            throw new Exception("Programmer error.");
        }
        autoconnect = settings.isAutoConnectEnabled();
        // Apply settings settings to controller.

        try {
            controller.setSingleStepMode(settings.isSingleStepMode());
            controller.setStatusUpdatesEnabled(settings.isStatusUpdatesEnabled());
            controller.setStatusUpdateRate(settings.getStatusUpdateRate());
        } catch (Exception ex) {
            StringBuilder message = new StringBuilder()
                    .append(Localization.getString("mainWindow.error.firmwareSetting"))
                    .append(": \n    ")
                    .append(Localization.getString("firmware.feature.maxCommandLength")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.truncateDecimal")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.singleStep")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.removeWhitespace")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.linesToArc")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.statusUpdates")).append(NEW_LINE)
                    .append(Localization.getString("firmware.feature.statusUpdateRate"));
            
            throw new Exception(message.toString(), ex);
        }
    }

    @Override
    public void dispatchMessage(MessageType messageType, String message) {
        messageService.dispatchMessage(messageType, message);
    }

    @Override
    public void setWorkPosition(Axis axis, double position) throws Exception {
        controller.setWorkPosition(axis, position);
    }

    @Override
    public void setWorkPositionUsingExpression(final Axis axis, final String expression) throws Exception {
        String expr = StringUtils.trimToEmpty(expression);
        expr = expr.replaceAll("#", String.valueOf(getWorkPosition().get(axis)));

        // If the expression starts with a mathimatical operation add the original position
        if (StringUtils.startsWithAny(expr, "/", "*")) {
            double value = getWorkPosition().get(axis);
            expr = value + " " + expr;
        }

        // Start a script engine and evaluate the expression
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine engine = mgr.getEngineByName("JavaScript");
        try {
            double position = Double.valueOf(engine.eval(expr).toString());
            setWorkPosition(axis, position);
        } catch (ScriptException e) {
            throw new Exception("Invalid expression", e);
        }
    }

    /////////////////////
    // Private functions.
    /////////////////////
    
    private boolean openCommConnection(String port, int baudRate) throws Exception {
        boolean connected;
        try {
            connected = controller.openCommPort(settings.getConnectionDriver(), port, baudRate);
            
            this.initializeProcessedLines(false, this.gcodeFile, this.gcp);
        } catch (Exception e) {
            logger.log(Level.INFO, "Exception in openCommConnection.", e);
            throw new Exception(Localization.getString("mainWindow.error.connection")
                    + " ("+ e.getClass().getName() + "): "+e.getMessage());
        }
        return connected;
    }

    private void initializeProcessedLines(boolean forceReprocess, File startFile, GcodeParser gcodeParser)
            throws Exception {
        if (startFile != null) {
            try (FileReader fr = new FileReader(startFile)) {
                Charset.forName(fr.getEncoding());
            }
            logger.info("Start preprocessing");
            long start = System.currentTimeMillis();
            if (this.processedGcodeFile == null || forceReprocess) {
                gcp.reset();

                String name = startFile.getName();

                // If this is being re-processed, strip the ugs postfix and try again.
                Pattern word = Pattern.compile("(.*)_ugs_[\\d]+$");
                Matcher match = word.matcher(name);
                if (match.matches()) {
                    name = match.group(1);
                }
                this.processedGcodeFile =
                        new File(this.getTempDir(), name + "_ugs_" + System.currentTimeMillis());
                this.preprocessAndExportToFile(gcodeParser, startFile, this.processedGcodeFile);

                // Store gcode file stats.
                GcodeStats gs = gcp.getCurrentStats();
                this.settings.setFileStats(new Settings.FileStats(
                    gs.getMin(), gs.getMax(), gs.getCommandCount()));
            }
            long end = System.currentTimeMillis();
            logger.log(Level.INFO, "Took {0}ms to preprocess", end - start);

            if (this.isConnected()) {
                this.estimatedSendDuration = -1L;

                Thread estimateThread = new Thread(() ->
                        estimatedSendDuration = controller.getJobLengthEstimate(processedGcodeFile));
                estimateThread.start();
            }
        }
    }
    
    private void sendUGSEvent(UGSEvent event, boolean force) {
        if (event.isControllerStatusEvent()) return;

        logger.log(Level.FINE, "Sending control state event {0}.", event.getEventType());
        if (event.isStateChangeEvent()) {
            if (this.controller != null && this.controller.handlesAllStateChangeEvents() && !force){
                return;
            }
        }
        
        for (UGSEventListener l : ugsEventListener) {
            l.UGSEvent(event);
        }
    }

    private void sendControllerStateEvent(UGSEvent event) {
        if (!event.isControllerStatusEvent()) return;

        for (ControllerStateListener l : controllerStateListener) {
            l.UGSEvent(event);
        }
    }

    @Override
    public void sendOverrideCommand(Overrides override) throws Exception {
        this.controller.sendOverrideCommand(override);
    }

    @Override
    public void settingChanged() {
        this.sendUGSEvent(new UGSEvent(UGSEvent.EventType.SETTING_EVENT), false);
    }

    @Override
    public void onUpdatedFirmwareSetting(FirmwareSetting setting) {
        this.sendUGSEvent(new UGSEvent(UGSEvent.EventType.FIRMWARE_SETTING_EVENT), false);
    }

    @Override
    public void onMessage(MessageType messageType, String message) {
        if (messageType == MessageType.ERROR) {
            FireControlDialogs.showErrorDialog("board error", message);
        }
    }

}