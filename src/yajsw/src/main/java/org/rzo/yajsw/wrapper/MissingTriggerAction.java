/*******************************************************************************
 * Copyright  2015 rzorzorzo@users.sf.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.rzo.yajsw.wrapper;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.rzo.yajsw.util.Cycler;

class MissingTriggerAction implements TriggerAction
{
	volatile private Cycler _cycler;
	volatile private int _counter = 0;
	private int _count;
	private TriggerAction[] _actions;
	Executor _executor;
	String _id;

	MissingTriggerAction(String id, Executor executor, long period, int count,
			TriggerAction[] actions, final boolean autoStop, final Logger logger)
	{
		_count = count;
		_executor = executor;
		_actions = actions;
		_id = id;
		_cycler = new Cycler(period, period, executor, new Runnable()
		{
			public void run()
			{
				// System.out.println("missing trigger "+_counter + " "+_count);
				if (_counter < _count)
				{
					if (autoStop)
						_cycler.stop();
					for (final TriggerAction action : _actions)
						if (action != null)
						{
							// run the action in a separate thread, because on
							// restart the cycler thread will be interrupted
							_executor.execute(new Runnable()
							{
								public void run()
								{
									// TODO add logger
									logger.info("missing trigger executed, found # "
											+ _counter
											+ " triggers during check period");
									action.execute("");
								}
							});
						}
				}
				else
					_counter = 0;
			}
		});
	}

	void start()
	{
		_cycler.start();
	}

	void stop()
	{
		_cycler.stop();
	}

	public Object execute(String line)
	{
		_counter++;
		return null;
	}

	public String getId()
	{
		return _id;
	}

}
