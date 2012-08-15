package ru.intellijeval;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.unscramble.UnscrambleDialog;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.util.GroovyScriptEngine;
import org.codehaus.groovy.control.CompilationFailedException;

import java.io.*;
import java.net.URL;
import java.util.*;

import static ru.intellijeval.Util.displayInConsole;

/**
 * @author DKandalov
 */
public class EvaluateAction extends AnAction {

	private static final String MAIN_SCRIPT = "plugin.groovy";
	private static final String CLASSPATH_PREFIX = "//-- classpath: ";

	private List<String> loadingErrors;
	private LinkedHashMap<String, Exception> evaluationExceptions;

	public EvaluateAction() {
		super(Util.EVAL_ICON);
	}

	@Override
	public void actionPerformed(AnActionEvent event) {
		evaluateAllPlugins(event);
	}

	private void evaluateAllPlugins(AnActionEvent event) {
		FileDocumentManager.getInstance().saveAllDocuments();

		loadingErrors = new LinkedList<String>();
		evaluationExceptions = new LinkedHashMap<String, Exception>();

		for (Map.Entry<String, String> entry : EvalComponent.pluginToPathMap().entrySet()) {
			String pluginId = entry.getKey();
			String path = entry.getValue();

			String mainScriptPath = findMainScriptIn(path);
			if (mainScriptPath == null) {
				addLoadingError(pluginId, "Couldn't find main script");
				continue;
			}

			try {

				GroovyScriptEngine scriptEngine = new GroovyScriptEngine(path, createClassLoaderWithDependencies(mainScriptPath, pluginId));
				Binding binding = new Binding();
				binding.setProperty("actionEvent", event);
				binding.setVariable("event", event);
				scriptEngine.run(mainScriptPath, binding);

			} catch (IOException e) {
				addLoadingError(pluginId, "Error while creating scripting engine. " + e.getMessage());
			} catch (CompilationFailedException e) {
				addLoadingError(pluginId, "Error while compiling script. " + e.getMessage());
			} catch (Exception e) {
				addEvaluationException(pluginId, e);
			}
		}

		reportLoadingErrors(event);
		reportEvaluationExceptions(event);
	}

	private String findMainScriptIn(String path) {
		List<File> files = allFilesInDirectory(new File(path));
		List<File> result = new ArrayList<File>();
		for (File file : files) {
			if (MAIN_SCRIPT.equals(file.getName())) {
				result.add(file);
			}
		}
		if (result.size() == 0) return null;
		else if (result.size() == 1) return result.get(0).getAbsolutePath();
		else throw new IllegalStateException("Found several " + MAIN_SCRIPT + " files under " + path);
	}

	private GroovyClassLoader createClassLoaderWithDependencies(String mainScriptPath, String pluginId) {
		GroovyClassLoader classLoader = new GroovyClassLoader(this.getClass().getClassLoader());

		try {
			classLoader.addURL(new URL("file://" + new File(mainScriptPath).getParent()));
			classLoader.addClasspath(new File(mainScriptPath).getParent());

			BufferedReader inputStream = new BufferedReader(new InputStreamReader(new FileInputStream(mainScriptPath)));
			String line;
			while ((line = inputStream.readLine()) != null) {
				if (line.contains(CLASSPATH_PREFIX)) {
					String path = line.replace(CLASSPATH_PREFIX, "");
					List<String> filePaths = findAllFilePaths(path);
					if (filePaths.isEmpty()) {
						addLoadingError(pluginId, "Couldn't find dependency '" + path + "'");
					}
					for (String filePath : filePaths) {
						classLoader.addURL(new URL("file://" + filePath)); // TODO not sure which of the below works properly
						classLoader.addClasspath(filePath);
					}
				}
			}
		} catch (IOException e) {
			addLoadingError(pluginId, "Error while looking for dependencies. Main script: " + mainScriptPath + ". " + e.getMessage());
		}
		return classLoader;
	}

	private List<String> findAllFilePaths(String path) {
		File file = new File(path);
		if (!file.exists()) {
			return Collections.emptyList();
		}
		if (file.isFile()) return Collections.singletonList(path);
		if (file.isDirectory()) {
			List<File> allFiles = allFilesInDirectory(file);
			List<String> result = new LinkedList<String>();
			for (File aFile : allFiles) {
				result.add(aFile.getAbsolutePath());
			}
			return result;
		}
		throw new IllegalStateException();
	}

	private static List<File> allFilesInDirectory(File dir) {
		LinkedList<File> result = new LinkedList<File>();
		File[] files = dir.listFiles();
		if (files == null) return result;

		for (File file : files) {
			if (file.isFile()) {
				result.add(file);
			} else if (file.isDirectory()) {
				result.addAll(allFilesInDirectory(file));
			}
		}
		return result;
	}

	private void reportLoadingErrors(AnActionEvent actionEvent) {
		StringBuilder text = new StringBuilder();
		for (String s : loadingErrors) text.append(s);
		if (text.length() > 0)
			displayInConsole("Loading errors", text.toString(), ConsoleViewContentType.ERROR_OUTPUT, actionEvent.getData(PlatformDataKeys.PROJECT));
	}

	@SuppressWarnings("ThrowableResultOfMethodCallIgnored")
	private void reportEvaluationExceptions(AnActionEvent actionEvent) {
		for (Map.Entry<String, Exception> entry : evaluationExceptions.entrySet()) {
			StringWriter writer = new StringWriter();
			entry.getValue().printStackTrace(new PrintWriter(writer));
			String s = UnscrambleDialog.normalizeText(writer.getBuffer().toString());

			displayInConsole(entry.getKey(), s, ConsoleViewContentType.ERROR_OUTPUT, actionEvent.getData(PlatformDataKeys.PROJECT));
		}
	}

	private void addEvaluationException(String pluginId, Exception e) {
		//noinspection ThrowableResultOfMethodCallIgnored
		evaluationExceptions.put(pluginId, e);
	}

	private void addLoadingError(String pluginId, String message) {
		loadingErrors.add("Plugin: " + pluginId + ". " + message);
	}
}
