package com.mobilesorcery.sdk.html5.debug.jsdt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.internal.localstore.IsSynchronizedVisitor;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.wst.jsdt.debug.core.breakpoints.IJavaScriptLineBreakpoint;
import org.eclipse.wst.jsdt.debug.core.jsdi.BooleanValue;
import org.eclipse.wst.jsdt.debug.core.jsdi.Location;
import org.eclipse.wst.jsdt.debug.core.jsdi.NullValue;
import org.eclipse.wst.jsdt.debug.core.jsdi.NumberValue;
import org.eclipse.wst.jsdt.debug.core.jsdi.ScriptReference;
import org.eclipse.wst.jsdt.debug.core.jsdi.StringValue;
import org.eclipse.wst.jsdt.debug.core.jsdi.ThreadReference;
import org.eclipse.wst.jsdt.debug.core.jsdi.UndefinedValue;
import org.eclipse.wst.jsdt.debug.core.jsdi.Value;
import org.eclipse.wst.jsdt.debug.core.jsdi.VirtualMachine;
import org.eclipse.wst.jsdt.debug.core.jsdi.event.EventQueue;
import org.eclipse.wst.jsdt.debug.core.jsdi.event.ThreadExitEvent;
import org.eclipse.wst.jsdt.debug.core.jsdi.request.EventRequest;
import org.eclipse.wst.jsdt.debug.core.jsdi.request.EventRequestManager;
import org.eclipse.wst.jsdt.debug.core.model.IJavaScriptDebugTarget;
import org.eclipse.wst.jsdt.debug.core.model.JavaScriptDebugModel;
import org.eclipse.wst.jsdt.debug.internal.core.model.JavaScriptDebugTarget;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.mobilesorcery.sdk.core.CoreMoSyncPlugin;
import com.mobilesorcery.sdk.core.MoSyncProject;
import com.mobilesorcery.sdk.core.MoSyncTool;
import com.mobilesorcery.sdk.core.Util;
import com.mobilesorcery.sdk.html5.Html5Plugin;
import com.mobilesorcery.sdk.html5.debug.IRedefinable;
import com.mobilesorcery.sdk.html5.debug.IRedefineListener;
import com.mobilesorcery.sdk.html5.debug.JSODDSupport;
import com.mobilesorcery.sdk.html5.debug.LocalVariableScope;
import com.mobilesorcery.sdk.html5.debug.hotreplace.ProjectRedefinable;
import com.mobilesorcery.sdk.html5.live.ILiveServerListener;
import com.mobilesorcery.sdk.html5.live.LiveServer;

public class ReloadVirtualMachine implements VirtualMachine, ILiveServerListener {

	private final LiveServer server;
	private List threads = new ArrayList();
	private final ReloadEventRequestManager requestMgr;
	private ReloadEventQueue eventQueue;
	private final NullValue nullValue;
	private final ReloadUndefinedValue undefValue;
	private int currentSessionId = LiveServer.NO_SESSION;
	private IProject project;
	private ReloadThreadReference mainThread;
	private boolean isTerminated = false;
	private ProjectRedefinable snapshot;
	private ILaunch launch;
	private IJavaScriptDebugTarget debugTarget;

	public ReloadVirtualMachine(int port) throws Exception {
		// TODO: PORT
		server = Html5Plugin.getDefault().getReloadServer();
		// JUST ONE MAIN THREAD
		mainThread = new ReloadThreadReference(this);
		threads.add(mainThread);
		resetThreads();

		requestMgr = new ReloadEventRequestManager(this);
		eventQueue = new ReloadEventQueue(this, requestMgr);
		
		nullValue = new ReloadNullValue(this);
		undefValue = new ReloadUndefinedValue(this);

		server.addListener(this);
		server.startServer(this);
		server.registerVM(this);
	}

	private void resetThreads() {
		mainThread.markSuspended(false, false);
	}
	
	private void resetEventQueue() {
		if (eventQueue != null) {
			List exitRequests = requestMgr.threadExitRequests();
			for (Object exitRequest : exitRequests) {
				eventQueue.received(ReloadEventQueue.CUSTOM_EVENT, new ReloadThreadExitEvent(this, mainThread, null, (EventRequest) exitRequest));
			}
		}
	}

	public void setCurrentSessionId(int sessionId) {
		this.currentSessionId = sessionId;
	}

	@Override
	public void resume() {
		server.resume(currentSessionId);
	}

	public void step(int stepType) {
		server.step(currentSessionId, stepType);
	}

	@Override
	public void suspend() {
		mainThread.suspend();
	}

	public void suspend(boolean isThread) {
		server.suspend(currentSessionId);
	}

	public void reset(int newSessionId, MoSyncProject project) {
		resetThreads();
		if (currentSessionId != LiveServer.NO_SESSION) {
			server.reset(currentSessionId);
			resetEventQueue();
		}
		setCurrentSessionId(newSessionId);
		this.project = project.getWrappedProject();
	}

	@Override
	public synchronized void terminate() {
		try {
			server.terminate(currentSessionId);
			server.removeListener(this);
			server.stopServer(this);
		} catch (Exception e) {
			CoreMoSyncPlugin.getDefault().log(e);
		} finally {
			eventQueue.close();
			isTerminated = true;
		}
	}

	@Override
	public String name() {
		return "Reload";
	}

	@Override
	public String description() {
		return "TODO";
	}

	@Override
	public String version() {
		return MoSyncTool.getDefault().getVersionInfo(MoSyncTool.BINARY_VERSION);
	}

	@Override
	public List allThreads() {
		return threads;
	}

	@Override
	public List allScripts() {
		ArrayList<ScriptReference> result = new ArrayList<ScriptReference>();
		// Before the project has been initialized, we just send all the scripts in the workspace...
		ArrayList<IProject> projects = new ArrayList<IProject>();
		if (project != null) {
			projects.add(project);
		} else {
			projects.addAll(Arrays.asList(ResourcesPlugin.getWorkspace().getRoot().getProjects()));
		}

		for (IProject project : projects) {
			JSODDSupport jsoddSupport = Html5Plugin.getDefault().getJSODDSupport(project);
			if (jsoddSupport != null) {
				Set<IPath> allFiles = jsoddSupport.getAllFiles();
				for (IPath file : allFiles) {
					SimpleScriptReference ref = new SimpleScriptReference(this, file);
					result.add(ref);
				}
			}
		}
		return result;
	}
	
	public IProject getProject() {
		return project;
	}

	@Override
	public void dispose() {
		terminate();
	}

	@Override
	public UndefinedValue mirrorOfUndefined() {
		return undefValue;
	}

	@Override
	public NullValue mirrorOfNull() {
		return nullValue;
	}

	@Override
	public BooleanValue mirrorOf(boolean bool) {
		return new ReloadBooleanValue(this, bool);
	}

	@Override
	public NumberValue mirrorOf(Number number) {
		return new ReloadNumberValue(this, number);
	}

	@Override
	public StringValue mirrorOf(String string) {
		return new ReloadStringValue(this, string);
	}

	@Override
	public EventRequestManager eventRequestManager() {
		return requestMgr;
	}

	@Override
	public EventQueue eventQueue() {
		return eventQueue;
	}

	/**
	 * Evaluates an expression in the current scope.
	 * @param expression The JavaScript expression to evaluate
	 * @return The result of the evaluation
	 * @throws InterruptedException If the waiting thread was interrupted,
	 * for example by a terminate request.
	 * @throws TimeoutException If the client failed to respond within
	 * a specified timeout.
	 */
	public Object evaluate(String expression) throws InterruptedException, TimeoutException {
		return evaluate(expression, null);
	}


	/**
	 * Evaluates an expression at a specified stack depth
	 * @param expression The JavaScript expression to evaluate
	 * @param stackDepth The stackdepth to perform the evaluation, or {@code null} to
	 * use the current scope.
	 * @return The result of the evaluation
	 * @throws InterruptedException If the waiting thread was interrupted,
	 * for example by a terminate request.
	 * @throws TimeoutException If the client failed to respond within
	 * a specified timeout.
	 */
	public Object evaluate(String expression, Integer stackDepth) throws InterruptedException, TimeoutException {
		return server.evaluate(currentSessionId, expression, stackDepth);
	}
	
	/**
	 * Issues a reload request to the client.
	 * @param resourcePath The resource to reload.
	 * A {@code null} value will cause a full reload of the app.
	 * @param resourcePath The resource to upload, relative
	 * to the project's HTML5 location
	 * @param reloadHint If applicable; whether to reload the page
	 * @return {@code true} If this virtual machine accepted the
	 * file for updating.
	 */
	public boolean update(IFile resource) {
		boolean doUpdate = resource != null && resource.getProject().equals(project);
		if (doUpdate) {
			server.update(currentSessionId, resource);
		}
		return doUpdate;
	}
	
	public void reload() {
		server.reload(currentSessionId);
	}
	
	/**
	 * Updates a function reference on the client.
	 * @param key
	 * @param source
	 */
	public void updateFunction(String key, String source) {
		server.updateFunction(currentSessionId, key, source);
	}


	@Override
	public void received(String command, JSONObject json) {
		// TODO!!! Session id - now all will suspend.
		// TODID -- filtering is done in the eventqueue. For now.

		// MAIN THREAD
		ReloadThreadReference thread = (ReloadThreadReference) threads.get(0);
		boolean isClientSuspend = Boolean.parseBoolean("" + json.get("suspended"));
		if (thread.isSuspended() && !isClientSuspend) {
			return;
		}
		thread.markSuspended(true);
		JSONArray array = (JSONArray) json.get("stack");
		ReloadStackFrame[] frames = new ReloadStackFrame[array.size()];
		for (int i = 0; i < array.size(); i++) {
			ReloadStackFrame frame = new ReloadStackFrame(this, json, i);
			// Stack traces are reported in the reverse order.
			frames[array.size() - 1 - i] = frame;
		}
		if (frames.length == 0) {
			frames = new ReloadStackFrame[1];
			frames[0] = new ReloadStackFrame(this, json, -1);
		}
		thread.setFrames(frames);
		//suspend();
		eventQueue.received(command, json);
	}

	public LocalVariableScope getLocalVariableScope(ScriptReference ref, int line) {
		// TODO: Faster?
		if (ref instanceof SimpleScriptReference) {
			IFile file = ((SimpleScriptReference) ref).getFile();
			JSODDSupport jsoddSupport = Html5Plugin.getDefault().getJSODDSupport(file.getProject());
			LocalVariableScope scope = jsoddSupport.getScope(file, line);
			if (scope != null) {
				return scope;
			}
		}
		return null;
	}

	public int getCurrentSessionId() {
		return currentSessionId;
	}

	@Override
	public String toString() {
		return "JavaScript On-Device Debug VM, session id #" + getCurrentSessionId();
	}

	public void setCurrentLocation(String location) {
		mainThread.setCurrentLocation(location);
	}

	public boolean isTerminated() {
		return isTerminated;
	}

	public ReloadThreadReference mainThread() {
		return mainThread;
	}

	public void dropToFrame(int dropToFrame) throws DebugException {
		IThread[] threads = getJavaScriptDebugTarget().getThreads();
		for (int i = 0; i < threads.length; i++) {
			IThread thread = threads[i];
			ReloadDropToFrame.dropToFrame(thread, dropToFrame);
		}
	}

	private IJavaScriptDebugTarget getJavaScriptDebugTarget() {
		return debugTarget;
	}

	public ProjectRedefinable getSnapshot() {
		return snapshot;
	}

	public void setSnapshot(ProjectRedefinable snapshot) {
		this.snapshot = snapshot;
	}

	public void setLaunch(ILaunch launch) {
		this.launch = launch;
	}

	public void setDebugTarget(IJavaScriptDebugTarget debugTarget) {
		this.debugTarget = debugTarget;
	}

}
