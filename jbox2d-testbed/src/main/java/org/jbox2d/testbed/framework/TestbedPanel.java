/*******************************************************************************
 * Copyright (c) 2011, Daniel Murphy
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the <organization> nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL DANIEL MURPHY BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
/**
 * Created at 3:13:48 AM Jul 17, 2010
 */
package org.jbox2d.testbed.framework;

import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import org.jbox2d.callbacks.DebugDraw;

/**
 * A TestbedPanel encapsulates the graphical panel displayed to the user.  Also it
 * is responsible for populating panel-specific data in the model (like panel width).
 * 
 * @author Daniel Murphy
 */
public interface TestbedPanel {

  /**
   * Adds a key listener
   * @param argListener
   */
  public void addKeyListener(KeyListener argListener);

  /**
   * Adds a mouse listener
   * @param argListener
   */
  public void addMouseListener(MouseListener argListener);

  /**
   * Adds a mouse motion listener
   * @param argListener
   */
  public void addMouseMotionListener(MouseMotionListener argListener);

  public int getHeight();

  public int getWidth();

  public void grabFocus();
  
  public DebugDraw getDebugDraw();

  /**
   * Renders the world
   */
  public void render();

  /**
   * Paints the world to the screen
   */
  public void paintScreen();
}
