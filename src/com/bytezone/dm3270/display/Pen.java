package com.bytezone.dm3270.display;

import com.bytezone.dm3270.attributes.Attribute;
import com.bytezone.dm3270.attributes.StartFieldAttribute;

import javafx.scene.paint.Color;

public interface Pen
{
  ScreenContext getDefaultScreenContext ();

  void reset ();

  void startField (StartFieldAttribute startFieldAttribute);

  void addAttribute (Attribute attribute);

  int getPosition ();

  void setForeground (Color color);

  void setBackground (Color color);

  void setHighlight (byte value);

  void setHighIntensity (boolean value);

  void reset (byte value);

  void writeGraphics (byte b);

  void write (byte b);

  void moveRight ();

  void eraseEOF ();

  void tab ();

  void moveTo (int position);
}