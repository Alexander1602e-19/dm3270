package com.bytezone.dm3270.display;

import static com.bytezone.dm3270.application.Console.Function.TERMINAL;
import static com.bytezone.dm3270.attributes.Attribute.AttributeType.RESET;
import static com.bytezone.dm3270.commands.AIDCommand.NO_AID_SPECIFIED;
import static com.bytezone.dm3270.orders.Order.SET_ATTRIBUTE;
import static com.bytezone.dm3270.orders.Order.START_FIELD;
import static com.bytezone.dm3270.orders.Order.START_FIELD_EXTENDED;
import static com.bytezone.dm3270.structuredfields.SetReplyMode.RM_CHARACTER;

import java.awt.Toolkit;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.bytezone.dm3270.application.Console.Function;
import com.bytezone.dm3270.application.ConsolePane;
import com.bytezone.dm3270.attributes.Attribute;
import com.bytezone.dm3270.attributes.ColorAttribute;
import com.bytezone.dm3270.attributes.StartFieldAttribute;
import com.bytezone.dm3270.commands.AIDCommand;
import com.bytezone.dm3270.filetransfer.FileStage;
import com.bytezone.dm3270.filetransfer.FileTransferOutbound;
import com.bytezone.dm3270.filetransfer.Transfer;
import com.bytezone.dm3270.jobs.BatchJob;
import com.bytezone.dm3270.jobs.JobStage;
import com.bytezone.dm3270.orders.BufferAddress;
import com.bytezone.dm3270.orders.Order;
import com.bytezone.dm3270.plugins.Plugin;
import com.bytezone.dm3270.plugins.PluginData;
import com.bytezone.dm3270.plugins.PluginsStage;
import com.bytezone.dm3270.plugins.ScreenField;
import com.bytezone.dm3270.structuredfields.SetReplyMode;

public class Screen extends Canvas
{
  private static final byte[] buffer = new byte[4096];

  private final ScreenPosition[] screenPositions;
  private final CharacterSize characterSize;        // contains font-specific values
  private final FieldManager fieldManager = new FieldManager (this);
  private final ContextManager contextHandler = new ContextManager ();
  private FontManager fontManager;
  private final Cursor cursor = new Cursor (this);
  private final Function function;
  private ConsolePane consolePane;

  private final int xOffset = 4;      // padding left and right
  private final int yOffset = 4;      // padding top and bottom

  private int insertedCursorPosition = -1;
  private boolean keyboardLocked;
  private boolean insertMode;
  private boolean readModifiedAll = false;

  private final JobStage jobStage = new JobStage ();

  private FileStage fileStage;
  private Transfer currentTransfer;

  private final boolean recording = true;
  private final ScreenHistory screenHistory = new ScreenHistory ();

  private byte currentAID;
  private byte replyMode;
  private byte[] replyTypes = new byte[0];

  private PluginsStage pluginsStage;
  private int sequence;

  public final int rows;
  public final int columns;
  public final int screenSize;

  //  private boolean hasCommandField;

  public enum BuildInstruction
  {
    BUILD_FIELDS, DONT_BUILD_FIELDS
  }

  public Screen (int rows, int columns, Preferences prefs, Function function)
  {
    this.rows = rows;
    this.columns = columns;
    screenSize = rows * columns;
    this.function = function;

    String fontSelected = prefs.get ("FontName", "Monospaced");
    String sizeSelected = prefs.get ("FontSize", "16");

    GraphicsContext gc = getGraphicsContext2D ();
    characterSize = new CharacterSize ();
    setFont (fontSelected, Integer.parseInt (sizeSelected));

    screenPositions = new ScreenPosition[rows * columns];
    ScreenContext baseContext = contextHandler.getBase ();
    for (int i = 0; i < screenPositions.length; i++)
      screenPositions[i] = new ScreenPosition (gc, characterSize, baseContext);

    addTSOCommandStatusChangeListener (jobStage);
  }

  public void setFontManager (FontManager fontManager)
  {
    this.fontManager = fontManager;
  }

  public void doFontSmaller ()
  {
    fontManager.smaller ();
  }

  private void setFont (String name, int size)
  {
    characterSize.changeFont (name, size);

    setWidth (characterSize.getWidth () * columns + xOffset * 2);
    setHeight (characterSize.getHeight () * rows + yOffset * 2);

    getGraphicsContext2D ().setFont (characterSize.getFont ());
  }

  public void adjustFont (String name, int size)
  {
    if (name.equals (characterSize.getName ()) && size == characterSize.getSize ())
      return;

    setFont (name, size);
    ((Stage) getScene ().getWindow ()).sizeToScene ();

    eraseScreen ();
    drawScreen ();
  }

  public String getFontName ()
  {
    return characterSize.getName ();
  }

  public int getFontSize ()
  {
    return characterSize.getSize ();
  }

  public void setFileStage (FileStage fileStage)
  {
    this.fileStage = fileStage;
  }

  public FileStage getFileStage ()
  {
    return fileStage;
  }

  public Transfer openTransfer (FileTransferOutbound transferRecord)
  {
    if (currentTransfer != null)
      if (fileStage != null)
        fileStage.addTransfer (currentTransfer);

    currentTransfer = new Transfer ();
    currentTransfer.add (transferRecord);
    return currentTransfer;
  }

  public Transfer getTransfer ()
  {
    return currentTransfer;
  }

  public Transfer closeTransfer (FileTransferOutbound transferRecord)
  {
    if (currentTransfer == null)
    {
      System.out.println ("Null");
      return null;
    }

    Transfer transfer = currentTransfer;
    currentTransfer.add (transferRecord);

    if (fileStage != null)
    {
      fileStage.addTransfer (currentTransfer);
    }
    currentTransfer = null;

    return transfer;
  }

  public void closeTransfer ()
  {
    currentTransfer = null;
  }

  public Function getFunction ()
  {
    return function;
  }

  public void setConsolePane (ConsolePane consolePane)
  {
    this.consolePane = consolePane;
  }

  public void setPlugins (PluginsStage pluginsStage)
  {
    this.pluginsStage = pluginsStage;
    pluginsStage.setScreen (this);
  }

  public void displayText (String text)
  {
    GraphicsContext gc = getGraphicsContext2D ();
    gc.setFill (Color.BLACK);
    gc.fillRect (0, 0, getWidth (), getHeight ());
    gc.setFill (ColorAttribute.colors[5]);
    int x = 120;
    int y = 100;
    int height = 20;

    String[] lines = text.split ("\n");
    for (String line : lines)
    {
      gc.fillText (line, x, y);
      y += height;
    }
  }

  public int validate (int position)
  {
    while (position < 0)
      position += screenSize;
    while (position >= screenSize)
      position -= screenSize;
    return position;
  }

  public Cursor getScreenCursor ()
  {
    return cursor;
  }

  public ScreenPosition getScreenPosition (int position)
  {
    return screenPositions[position];
  }

  public ScreenPosition[] getScreenPositions ()
  {
    return screenPositions;
  }

  public ContextManager getContextHandler ()
  {
    return contextHandler;
  }

  public void resetInsertMode ()
  {
    if (insertMode)
      toggleInsertMode ();
  }

  public void toggleInsertMode ()
  {
    insertMode = !insertMode;
    notifyKeyboardStatusChange ("");
  }

  public boolean isInsertMode ()
  {
    return insertMode;
  }

  public void insertCursor ()
  {
    insertedCursorPosition = cursor.getLocation ();    // move it here later
  }

  // called from EraseAllUnprotectedCommand.process()
  public void eraseAllUnprotected ()
  {
    Field firstUnprotectedField = fieldManager.eraseAllUnprotected ();

    restoreKeyboard ();
    resetModified ();
    setAID (AIDCommand.NO_AID_SPECIFIED);
    drawScreen ();

    if (firstUnprotectedField != null)
      cursor.moveTo (firstUnprotectedField.getFirstLocation ());
  }

  void drawPosition (int position, boolean hasCursor)
  {
    int row = position / columns;
    int col = position % columns;
    drawPosition (screenPositions[position], row, col, hasCursor);
  }

  public void buildFields ()
  {
    fieldManager.buildFields ();      // what about resetModified?

    boolean isTSOCommandScreen = fieldManager.isTSOCommandScreen ();
    Field tsoCommandField = fieldManager.getTSOCommandField ();
    notifyTSOCommandStatusChange (isTSOCommandScreen, tsoCommandField);
  }

  public void drawScreen ()
  {
    int pos = 0;
    for (int row = 0; row < rows; row++)
      for (int col = 0; col < columns; col++)
        drawPosition (screenPositions[pos++], row, col, false);

    if (insertedCursorPosition >= 0)
    {
      cursor.moveTo (insertedCursorPosition);
      insertedCursorPosition = -1;
      cursor.setVisible (true);
    }

    drawPosition (cursor.getLocation (), true);
  }

  private void drawPosition (ScreenPosition screenPosition, int row, int col,
      boolean hasCursor)
  {
    int x = xOffset + col * characterSize.getWidth ();
    int y = yOffset + row * characterSize.getHeight ();

    screenPosition.draw (x, y, hasCursor);
  }

  public void clearScreen ()
  {
    eraseScreen ();

    for (ScreenPosition sp : screenPositions)
      sp.reset ();

    cursor.moveTo (0);
  }

  private void eraseScreen ()
  {
    GraphicsContext gc = getGraphicsContext2D ();
    gc.setFill (Color.BLACK);
    gc.fillRect (0, 0, getWidth (), getHeight ());
  }

  @Override
  public boolean isResizable ()     // should apply to the Stage, not the Canvas
  {
    return false;
  }

  public int countFields ()
  {
    return fieldManager.size ();
  }

  public Field getField (int position)
  {
    return fieldManager.getField (position);
  }

  public List<Field> getFields ()
  {
    return fieldManager.getFields ();
  }

  public List<Field> getUnprotectedFields ()
  {
    return fieldManager.getUnprotectedFields ();
  }

  public Field getHomeField ()
  {
    List<Field> fields = getUnprotectedFields ();
    if (fields != null && fields.size () > 0)
      return fields.get (0);
    return null;
  }

  public void setAID (byte aid)
  {
    currentAID = aid;
  }

  public byte getAID ()
  {
    return currentAID;
  }

  public void setReplyMode (byte replyMode, byte[] replyTypes)
  {
    this.replyMode = replyMode;
    this.replyTypes = replyTypes;
  }

  public byte getReplyMode ()
  {
    return replyMode;
  }

  public byte[] getReplyTypes ()
  {
    return replyTypes;
  }

  // ---------------------------------------------------------------------------------//
  // Process plugins
  // ---------------------------------------------------------------------------------//

  // called from WriteCommand.process() after unlocking keyboard
  // will eventually be called before unlocking the keyboard
  public AIDCommand processPluginAuto ()
  {
    assert !keyboardLocked;

    if (pluginsStage != null && pluginsStage.activePlugins () > 0)
    {
      int cursorPosition = cursor.getLocation ();
      PluginData pluginData =
          fieldManager.getPluginScreen (sequence++, cursorPosition / columns,
                                        cursorPosition % columns);

      if (false)
      {
        System.out.println ("--------------------------------------------");
        System.out.println (pluginData);
      }

      pluginsStage.processAll (pluginData);
      AIDCommand command = processReply (pluginData);
      if (command != null)
        lockKeyboard (command.getKeyName ());
      return command;
    }
    return null;
  }

  // created by PluginsStage.itemSelected() -> PluginEntry.select()
  // which sets menuItem.setOnAction (e -> screen.processPluginRequest (plugin))
  public void processPluginRequest (Plugin plugin)
  {
    assert consolePane != null;
    int cursorPosition = cursor.getLocation ();
    PluginData pluginData =
        fieldManager.getPluginScreen (sequence++, cursorPosition / columns,
                                      cursorPosition % columns);
    plugin.processRequest (pluginData);
    AIDCommand command = processReply (pluginData);
    if (command != null)
    {
      lockKeyboard (command.getKeyName ());
      consolePane.sendAID (command);
    }
  }

  private AIDCommand processReply (PluginData data)
  {
    int currentLocation = cursor.getLocation ();

    boolean isVisible = cursor.isVisible ();
    if (isVisible)
      cursor.setVisible (false);

    for (ScreenField screenField : data.changedFields)
    {
      Field field = getField (screenField.location.location);   // first display location
      assert field != null;
      if (field != null)    // should be impossible
      {
        if (screenField.newData == null)
        {
          // erase field?
        }
        else
        {
          try
          {
            // should this type the characters instead?
            field.setText (screenField.newData.getBytes ("CP1047"));
            field.setModified (true);
          }
          catch (UnsupportedEncodingException e)
          {
            e.printStackTrace ();
          }
        }
        field.draw ();      // draws the field without a cursor
      }
    }

    if (data.cursorMoved ())
      cursor.moveTo (data.getNewCursorLocation ());
    else
      cursor.moveTo (currentLocation);

    if (isVisible)
      cursor.setVisible (true);

    if (data.getKey () != 0)
    {
      setAID (data.getKey ());
      AIDCommand command = readModifiedFields ();
      return command;
    }
    return null;
  }

  // ---------------------------------------------------------------------------------//
  // Convert screen contents to an AID command
  // ---------------------------------------------------------------------------------//

  // called from ConsoleKeyPress.handle() in response to a user command
  // called from this.readModifiedFields(0x..) below
  public AIDCommand readModifiedFields ()
  {
    // pack the AID
    int ptr = 0;
    buffer[ptr++] = currentAID;               // whatever key was pressed

    // PA keys and the CLR key only return the AID byte
    if (!readModifiedAll)
      if (currentAID == AIDCommand.AID_PA1 || currentAID == AIDCommand.AID_PA2
          || currentAID == AIDCommand.AID_PA3 || currentAID == AIDCommand.AID_CLEAR)
        return new AIDCommand (this, buffer, 0, ptr);

    // pack the cursor address
    BufferAddress ba = new BufferAddress (getScreenCursor ().getLocation ());
    ptr = ba.packAddress (buffer, ptr);

    // pack all modified fields
    for (Field field : getUnprotectedFields ())
      if (field.isModified ())
        ptr = packField (field, buffer, ptr);

    return new AIDCommand (this, buffer, 0, ptr);
  }

  // Called from ReadCommand.process() in response to a ReadBuffer (F2) command
  // Called from ReadPartitionSF.process() in response to a ReadBuffer (F2) command
  public AIDCommand readBuffer ()
  {
    // pack the AID
    int ptr = 0;
    buffer[ptr++] = currentAID;

    // pack the cursor address
    BufferAddress ba = new BufferAddress (getScreenCursor ().getLocation ());
    ptr = ba.packAddress (buffer, ptr);

    // pack every screen location
    for (ScreenPosition sp : screenPositions)
      if (sp.isStartField ())
        ptr = packStartPosition (sp, buffer, ptr);
      else
        ptr = packDataPosition (sp, buffer, ptr);       // don't suppress nulls

    return new AIDCommand (this, buffer, 0, ptr);
  }

  // Called from ReadCommand.process() in response to a ReadModified (F6)
  // or a ReadModifiedAll (6E) command
  // Called from ReadPartitionSF.process() in response to a ReadModified (F6)
  // or a ReadModifiedAll (6E) command
  public AIDCommand readModifiedFields (byte type)
  {
    switch (type)
    {
      case (byte) 0xF6:
        return readModifiedFields ();

      case 0x6E:
        readModifiedAll = true;
        AIDCommand command = readModifiedFields ();
        readModifiedAll = false;
        return command;

      default:
        System.out.println ("Unknown type in Screen.readModifiedFields()");
    }

    return null;
  }

  // ---------------------------------------------------------------------------------//
  // Pack ScreenPosition routines - based on current ReplyMode setting
  // ---------------------------------------------------------------------------------//

  private int packStartPosition (ScreenPosition sp, byte[] buffer, int ptr)
  {
    assert sp.isStartField ();

    StartFieldAttribute sfa = sp.getStartFieldAttribute ();

    if (replyMode == SetReplyMode.RM_FIELD)
    {
      buffer[ptr++] = START_FIELD;
      buffer[ptr++] = sfa.getAttributeValue ();
    }
    else
    {
      buffer[ptr++] = START_FIELD_EXTENDED;

      List<Attribute> attributes = sp.getAttributes ();
      buffer[ptr++] = (byte) (attributes.size () + 1);    // +1 for StartFieldAttribute

      ptr = sfa.pack (buffer, ptr);                       // pack the SFA first
      for (Attribute attribute : attributes)
        ptr = attribute.pack (buffer, ptr);               // then pack the rest
    }
    return ptr;
  }

  private int packDataPosition (ScreenPosition sp, byte[] buffer, int ptr)
  {
    if (replyMode == RM_CHARACTER)
      for (Attribute attribute : sp.getAttributes ())
        if (attribute.getAttributeType () == RESET)
        {
          buffer[ptr++] = SET_ATTRIBUTE;
          ptr = attribute.pack (buffer, ptr);
        }
        else
          for (byte b : replyTypes)
            if (attribute.matches (b))
            {
              buffer[ptr++] = Order.SET_ATTRIBUTE;
              ptr = attribute.pack (buffer, ptr);
              break;
            }

    if (sp.isGraphicsChar ())
      buffer[ptr++] = Order.GRAPHICS_ESCAPE;

    buffer[ptr++] = sp.getByte ();

    return ptr;
  }

  private int packField (Field field, byte[] buffer, int ptr)
  {
    assert field.isModified ();

    for (ScreenPosition sp : field)
      if (sp.isStartField ())
      {
        buffer[ptr++] = Order.SET_BUFFER_ADDRESS;
        BufferAddress ba = new BufferAddress (field.getFirstLocation ());
        ptr = ba.packAddress (buffer, ptr);
      }
      else if (!sp.isNull ())
        ptr = packDataPosition (sp, buffer, ptr);       // suppress nulls

    return ptr;
  }

  // ---------------------------------------------------------------------------------//
  // Events to be processed from WriteControlCharacter.process()
  // ---------------------------------------------------------------------------------//

  public void resetPartition ()
  {
  }

  public void startPrinter ()
  {
  }

  public void soundAlarm ()
  {
    Toolkit.getDefaultToolkit ().beep ();
  }

  public void restoreKeyboard ()
  {
    setAID (NO_AID_SPECIFIED);
    cursor.setVisible (true);
    keyboardLocked = false;
    notifyKeyboardStatusChange ("");
  }

  public void lockKeyboard (String keyName)
  {
    keyboardLocked = true;
    notifyKeyboardStatusChange (keyName);

    if (function == TERMINAL)
      cursor.setVisible (false);

    if (recording)
      screenHistory.add (copy ());
  }

  public void resetModified ()
  {
    for (Field field : getUnprotectedFields ())
      if (field.isModified ())
        field.setModified (false);
  }

  public boolean isKeyboardLocked ()
  {
    return keyboardLocked;
  }

  // ---------------------------------------------------------------------------------//
  // Batch jobs
  // ---------------------------------------------------------------------------------//

  public void batchJobSubmitted (int jobNumber, String jobName)
  {
    System.out.println ("Job submitted:");

    BatchJob batchJob = new BatchJob (jobNumber, jobName);
    jobStage.addBatchJob (batchJob);
    System.out.println (batchJob);
  }

  public void
      batchJobEnded (int jobNumber, String jobName, String time, int conditionCode)
  {
    System.out.println ("Job completed:");

    BatchJob batchJob = jobStage.getBatchJob (jobNumber);
    if (batchJob != null)
    {
      batchJob.completed (time, conditionCode);
      System.out.println (batchJob);
      jobStage.refreshJobTable ();            // temp fix before jdk 8u60
    }
  }

  public void showJobStage ()
  {
    jobStage.show ();
  }

  // ---------------------------------------------------------------------------------//
  // Listener events
  // ---------------------------------------------------------------------------------//

  private final Set<KeyboardStatusListener> keyboardStatusListeners = new HashSet<> ();

  void notifyKeyboardStatusChange (String keyName)
  {
    for (KeyboardStatusListener listener : keyboardStatusListeners)
      listener.keyboardStatusChanged (keyboardLocked, keyName, insertMode);
  }

  public void addStatusChangeListener (KeyboardStatusListener listener)
  {
    keyboardStatusListeners.add (listener);
  }

  public void removeStatusChangeListener (KeyboardStatusListener listener)
  {
    keyboardStatusListeners.remove (listener);
  }

  private final Set<TSOCommandStatusListener> tsoCommandStatusListeners =
      new HashSet<> ();

  void notifyTSOCommandStatusChange (boolean isTSOCommandScreen, Field tsoCommandField)
  {
    for (TSOCommandStatusListener listener : tsoCommandStatusListeners)
      listener.screenChanged (isTSOCommandScreen, tsoCommandField);
  }

  public void addTSOCommandStatusChangeListener (TSOCommandStatusListener listener)
  {
    tsoCommandStatusListeners.add (listener);
  }

  public void removeTSOCommandStatusChangeListener (TSOCommandStatusListener listener)
  {
    tsoCommandStatusListeners.remove (listener);
  }

  // ---------------------------------------------------------------------------------//
  // Screen image
  // ---------------------------------------------------------------------------------//

  // Create a copy of the current canvas - this will change to a Buffer reply
  private ImageView copy ()
  {
    WritableImage wim = new WritableImage ((int) getWidth (), (int) getHeight ());
    snapshot (null, wim);
    return new ImageView (wim);
  }

  public ScreenHistory pause ()           // triggered by cmd-s
  {
    if (screenHistory.size () == 0)
      return null;

    screenHistory.pause (keyboardLocked);
    keyboardLocked = true;
    return screenHistory;
  }

  public void resume ()                  // also triggered by cmd-s
  {
    keyboardLocked = screenHistory.resume ();
  }

  // ---------------------------------------------------------------------------------//
  // Debugging
  // ---------------------------------------------------------------------------------//

  public String getFieldText ()
  {
    return fieldManager.getFieldsText ();
  }

  public String getScreenText ()
  {
    StringBuilder text = new StringBuilder ();
    int pos = 0;
    for (ScreenPosition sp : screenPositions)
    {
      if (sp.isStartField ())
        text.append ("%");
      else
        text.append (sp.getChar ());
      if (++pos % columns == 0)
        text.append ("\n");
    }

    text.append ("\n");
    text.append (fieldManager.getTotalsText ());

    return text.toString ();
  }
}