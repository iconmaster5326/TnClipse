package info.iconmaster.tnclipse.launch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.IProcessFactory;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

import info.iconmaster.tnbox.model.TnBoxEnvironment;
import info.iconmaster.tnbox.model.TnBoxThread;

public class TnBoxProcessFactory implements IProcessFactory {
	public static final String ID = "info.iconmaster.tnclipse.launch.tnbox.process";
	
	public static class TnBoxProcess implements IProcess {
		private ILaunch launch;
		private String label;
		private IStreamsProxy proxy;
		private Map<String, String> attributes;
		private boolean terminated;
		
		public TnBoxEnvironment environ;
		public List<TnBoxThread> threads = new ArrayList<>();
		
		public TnBoxOutputStream out, err;
		public Queue<Character> in = new ArrayDeque<>();
		
		public TnBoxProcess(ILaunch launch, String label, Map<String, String> attributes) {
			this.launch = launch;
			this.label = label;
			this.attributes = attributes == null ? new HashMap<>() : attributes;
			
			out = new TnBoxOutputStream();
			err = new TnBoxOutputStream();
			
			proxy = new IStreamsProxy() {
				@Override
				public void write(String input) throws IOException {
					for (char c : input.toCharArray()) {
						in.add(c);
					}
				}
				
				@Override
				public IStreamMonitor getOutputStreamMonitor() {
					return out;
				}
				
				@Override
				public IStreamMonitor getErrorStreamMonitor() {
					return err;
				}
			};
			
			launch.addProcess(this);
			fireEvent(DebugEvent.CREATE);
		}
		
		public void run(TnBoxThread initThread, boolean setupEnviron) throws DebugException {
			environ = initThread.environ;
			threads.add(initThread);
			
			if (setupEnviron) {
				environ.out = new PrintStream(new OutputStream() {
					@Override
					public void write(int b) throws IOException {
						out.append(Character.toString((char) b));
					}
				}, true);
				environ.err = new PrintStream(new OutputStream() {
					@Override
					public void write(int b) throws IOException {
						err.append(Character.toString((char) b));
					}
				}, true);
				environ.in = new InputStream() {
					@Override
					public int read() throws IOException {
						return in.isEmpty() ? -1 : in.remove();
					}
				};
			}
			
			Job job = Job.create(getLabel(), monitor->{
				if (monitor == null) monitor = new NullProgressMonitor();
				
				while (true) {
					if (isTerminated()) {
						environ.out.flush(); environ.err.flush();
						return;
					}
					
					if (initThread.completed()) {
						environ.out.flush(); environ.err.flush();
						terminate();
						return;
					}
					
					initThread.step();
				}
			});
			job.schedule();
		}
		
		@Override
		public <T> T getAdapter(Class<T> adapter) {
			if (adapter.equals(TnBoxProcess.class) || adapter.equals(IProcess.class)) {
				return (T) this;
			}
			
			if (adapter.equals(IDebugTarget.class)) {
				for (IDebugTarget target : launch.getDebugTargets()) {
					if (this.equals(target.getProcess())) {
						return (T) target;
					}
				}
				return null;
			}
			
			if (adapter.equals(ILaunch.class)) {
				return (T) getLaunch();
			}
			
			if(adapter.equals(ILaunchConfiguration.class)) {
				return (T) getLaunch().getLaunchConfiguration();
			}
			
			return null;
		}
		
		@Override
		public boolean canTerminate() {
			return !terminated;
		}
		
		@Override
		public boolean isTerminated() {
			return terminated;
		}
		
		@Override
		public void terminate() throws DebugException {
			terminated = true;
			fireEvent(DebugEvent.TERMINATE);
		}
		
		@Override
		public String getLabel() {
			return label;
		}
		
		@Override
		public ILaunch getLaunch() {
			return launch;
		}
		
		@Override
		public IStreamsProxy getStreamsProxy() {
			return proxy;
		}
		
		@Override
		public void setAttribute(String key, String value) {
			attributes.put(key, value);
			fireEvent(DebugEvent.CHANGE);
		}
		
		@Override
		public String getAttribute(String key) {
			return attributes.get(key);
		}
		
		@Override
		public int getExitValue() throws DebugException {
			return 0;
		}
		
		public void fireEvent(int type) {
			DebugPlugin manager = DebugPlugin.getDefault();
			if (manager != null) {
				manager.fireDebugEventSet(new DebugEvent[] {new DebugEvent(this, type)});
			}
		}
	}
	
	public static class TnBoxOutputStream implements IStreamMonitor {
		private String contents = "";
		private List<IStreamListener> listeners = new ArrayList<>();
		
		@Override
		public void addListener(IStreamListener listener) {
			listeners.add(listener);
		}
		
		@Override
		public String getContents() {
			return contents;
		}
		
		@Override
		public void removeListener(IStreamListener listener) {
			listeners.remove(listener);
		}
		
		public void append(Object o) {
			contents += o;
			listeners.stream().forEach((l)->l.streamAppended(String.valueOf(o), this));
		}
	}
	
	@Override
	public IProcess newProcess(ILaunch launch, Process process, String label, Map<String, String> attributes) {
		return new TnBoxProcess(launch, label, attributes);
	}
}
