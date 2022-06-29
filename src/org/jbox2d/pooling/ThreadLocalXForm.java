package org.jbox2d.pooling;

import org.jbox2d.common.XForm;

public class ThreadLocalXForm extends ThreadLocal<XForm> {
	protected XForm initialValue(){
		return new XForm();
	}
}
