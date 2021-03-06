/*
 * Coverity Sonar Plugin
 * Copyright (c) 2017 Synopsys, Inc
 * support@coverity.com
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html.
 */

package org.sonar.plugins.coverity.batch;

import com.coverity.ws.v9.*;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultTextPointer;
import org.sonar.api.batch.fs.internal.DefaultTextRange;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.config.Settings;
import org.sonar.api.rule.RuleKey;
import org.sonar.plugins.coverity.CoverityPlugin;
import org.sonar.plugins.coverity.base.CoverityPluginMetrics;
import org.sonar.plugins.coverity.util.CoverityUtil;
import org.sonar.plugins.coverity.ws.CIMClient;
import org.sonar.plugins.coverity.ws.CIMClientFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.sonar.plugins.coverity.util.CoverityUtil.createURL;

public class CoveritySensor implements Sensor {
	private static final Logger LOG = LoggerFactory.getLogger(CoveritySensor.class);

	private final String HIGH = "High";
	private final String MEDIUM = "Medium";
	private final String LOW = "Low";

	private int totalDefects = 0;
	private int highImpactDefects = 0;
	private int mediumImpactDefects = 0;
	private int lowImpactDefects = 0;

	private String platform;
	private CIMClientFactory cimClientFactory;
	private HashMap<String, InputFile> localInputFiles;

	public CoveritySensor(CIMClientFactory cimClientFactory) {
		this.cimClientFactory = cimClientFactory;
		platform = System.getProperty("os.name");
	}

	@Override
	public void describe(SensorDescriptor descriptor) {

		String[] repositories = new String[CoverityPlugin.COVERITY_LANGUAGES.size()];
		for (int i = 0; i < CoverityPlugin.COVERITY_LANGUAGES.size(); i++) {
			repositories[i] = CoverityPlugin.REPOSITORY_KEY + "-" + CoverityPlugin.COVERITY_LANGUAGES.get(i);
		}

		descriptor.name(this.toString()).createIssuesForRuleRepositories(repositories)
				// Coverity project is the only required value which does not provide a default (other properties validates at
				// runtime)
				.requireProperties(CoverityPlugin.COVERITY_PROJECT);

	}

	@Override
	public void execute(SensorContext context) {
		Settings settings = context.settings();
		localInputFiles = new HashMap<String, InputFile>();

		boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);

		boolean incLegacy = settings.getBoolean(CoverityPlugin.COVERITY_LEGACY);
		if (incLegacy) {
			LOG.warn("Legacy defects are included");
		} else {
			LOG.info("Legacy defects are excluded");
		}

		int totalDefectsCounter = 0;
		int highImpactDefectsCounter = 0;
		int mediumImpactDefectsCounter = 0;
		int lowImpactDefectsCounter = 0;

		LOG.info(CoverityPlugin.COVERITY_ENABLE + "=" + enabled);

		if (!enabled) {
			return;
		}

		// make sure to use the right SAAJ library. The one included with some JREs is missing a required file (a
		// LocalStrings bundle)
		ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

		System.setProperty("javax.xml.soap.MetaFactory", "com.sun.xml.messaging.saaj.soap.SAAJMetaFactoryImpl");

		String covProject = settings.getString(CoverityPlugin.COVERITY_PROJECT);

		String covSrcDir = settings.getString(CoverityPlugin.COVERITY_SOURCE_DIRECTORY);

		/**
		 * Checks whether a project has been specified.
		 */
		if (covProject == null || covProject.isEmpty()) {
			LOG.error("Couldn't find project: " + covProject);
			Thread.currentThread().setContextClassLoader(oldCL);
			return;
		}

		CIMClient instance = cimClientFactory.create(settings);

		// find the configured project
		ProjectDataObj covProjectObj = null;
		try {
			covProjectObj = instance.getProject(covProject);
			if (covProjectObj == null) {
				LOG.error("Couldn't find project: " + covProject);
				Thread.currentThread().setContextClassLoader(oldCL);
				return;
			}
			LOG.info("Found project: " + covProject + " (" + covProjectObj.getProjectKey() + ")");

		} catch (IOException | CovRemoteServiceException_Exception e) {
			LOG.error("Error while trying to find project: " + covProject);
			Thread.currentThread().setContextClassLoader(oldCL);
			return;
		}

		try {
			
			LOG.info("Configuring server side defect filtering.");
			LOG.info("  - Include legacy defects ? : " + incLegacy);
			instance.filterSpec.getLegacyNameList().add(incLegacy ? "True" : "False");
			
			LOG.info("Fetching defects for project: " + covProject);
			List<MergedDefectDataObj> defects = instance.getDefects(covProject);
			Map<Long, StreamDefectDataObj> streamDefects = instance.getStreamDefectsForMergedDefects(defects);
			LOG.info("Found " + streamDefects.size() + " defects");

			List<File> listOfFiles = new ArrayList<File>();
			String sonarSourcesString = null;
			if (covSrcDir != null && !covSrcDir.isEmpty()) {
				sonarSourcesString = covSrcDir;
			} else {
				sonarSourcesString = settings.getString("sonar.sources");
			}
			if (sonarSourcesString != null && !sonarSourcesString.isEmpty()) {
				List<String> sonarSources = Arrays.asList(sonarSourcesString.split(","));
				for (String dir : sonarSources) {
					File folder = new File(dir);
					listOfFiles.addAll(CoverityUtil.listFiles(folder));
				}
			}

			for (MergedDefectDataObj mddo : defects) {

				String status = "";
				String impact = "";
				boolean isLegacy = false;

				List<DefectInstanceDataObj> didos = streamDefects.get(mddo.getCid()).getDefectInstances();

				if (didos == null || didos.isEmpty()) {
					LOG.info("The merged defect with CID " + mddo.getCid() + " has no defect instances defined.");
					continue;
				}

				impact = didos.get(0).getImpact().getDisplayName();

				List<DefectStateAttributeValueDataObj> listOfAttributes = mddo.getDefectStateAttributeValues();

				for (DefectStateAttributeValueDataObj defectAttribute : listOfAttributes) {
					if (defectAttribute.getAttributeDefinitionId().getName().equals("DefectStatus")) {
						status = defectAttribute.getAttributeValueId().getName();
					}
					if (defectAttribute.getAttributeDefinitionId().getName().equals("Legacy")) {
						isLegacy = "True".equals(defectAttribute.getAttributeValueId().getName());
					}
				}

				if (!incLegacy && isLegacy) {
					LOG.info("Skipping legacy defect (CID " + mddo.getCid() + ", status '" + status + "')");
					continue;
				}
				
				if ("Dismissed".equals(status) || "Fixed".equals(status) || "Absent Dismissed".equals(status)) {
					LOG.info("Skipping resolved defect (CID " + mddo.getCid() + ", status '" + status + "')");
					continue;
				}

				if (impact != null) {
					totalDefectsCounter++;
					if (impact.equals(HIGH)) {
						highImpactDefectsCounter++;
					} else if (impact.equals(MEDIUM)) {
						mediumImpactDefectsCounter++;
					} else {
						lowImpactDefectsCounter++;
					}
				}

				for (DefectInstanceDataObj dido : didos) {
					InputFile inputFile;
					// find the main event, so we can use its line number
					EventDataObj mainEvent = getMainEvent(dido);
					if (mainEvent == null) {
						continue;
					} else {
						String mainEventFilePath = getMainEventFilePath(mainEvent);
						if (StringUtils.isEmpty(mainEventFilePath)) {
							// In case main event's file path is missing,
							// use MergeDefectDataObj's file path as default
							mainEventFilePath = mddo.getFilePathname();
						}

						inputFile = findLocalFile(context, mainEventFilePath, listOfFiles);
						if (!validateInputFile(inputFile, mainEventFilePath, mddo.getCid())) {
							continue;
						}
					}

					String subcategory = dido.getSubcategory();

					if (StringUtils.isEmpty(subcategory)) {
						subcategory = "none";
					}

					ActiveRule ar = findActiveRule(context, dido.getDomain(), dido.getCheckerName(), subcategory,
							inputFile.language());

					LOG.debug("ar=" + ar);
					if (ar != null) {
						LOG.debug("instance=" + instance);
						LOG.debug("covProjectObj=" + covProjectObj);
						LOG.debug("mddo=" + mddo);
						LOG.debug("dido=" + dido);
						String message = getIssueMessage(instance, covProjectObj, mainEvent, dido, mddo);

						final DefaultTextPointer start = new DefaultTextPointer(mainEvent.getLineNumber(), 0);

						NewIssue issue = context.newIssue();

						NewIssueLocation issueLocation = issue.newLocation().on(inputFile).at(new DefaultTextRange(start, start))
								.message(message);

						issue.forRule(ar.ruleKey()).at(issueLocation);

						LOG.debug("issue=" + issue);
						issue.save();
					} else {
						LOG.info("Couldn't create issue: " + mddo.getCid());
					}
				}
			}
		} catch (IOException | CovRemoteServiceException_Exception e) {
			LOG.error("Error fetching defects", e);
		}

		totalDefects = totalDefectsCounter;
		highImpactDefects = highImpactDefectsCounter;
		mediumImpactDefects = mediumImpactDefectsCounter;
		lowImpactDefects = lowImpactDefectsCounter;

		Thread.currentThread().setContextClassLoader(oldCL);
		// Display a clickable Coverity Logo
		getCoverityLogoMeasures(context, instance, covProjectObj);
	}

	protected String getIssueMessage(	CIMClient instance,
																		ProjectDataObj covProjectObj,
																		EventDataObj mainEvent,
																		DefectInstanceDataObj dido,
																		MergedDefectDataObj mddo) {
		String url = getDefectURL(instance, covProjectObj, mddo);

		StringBuilder message = new StringBuilder();
		message.append("[" + mddo.getDisplayType() + "] ");

		if (mainEvent == null || StringUtils.isEmpty(mainEvent.getEventDescription())
				|| StringUtils.isEmpty(mainEvent.getEventTag())) {
			message.append(dido.getLongDescription());
		} else {
			message.append(mainEvent.getEventTag() + ": " + mainEvent.getEventDescription());
		}

		return StringEscapeUtils.unescapeHtml(message.toString()) + " ( CID " + mddo.getCid() + " : " + url + " )";
	}

	protected String getDefectURL(CIMClient instance, ProjectDataObj covProjectObj, MergedDefectDataObj mddo) {
		return String.format("%s://%s:%d/sourcebrowser.htm?projectId=%s&mergedDefectId=%d",
				instance.isUseSSL() ? "https" : "http", instance.getHost(), instance.getPort(), covProjectObj.getProjectKey(),
				mddo.getCid());
	}

	protected EventDataObj getMainEvent(DefectInstanceDataObj dido) {
		if (dido.getEvents() != null && !dido.getEvents().isEmpty()) {
			for (EventDataObj edo : dido.getEvents()) {
				if (edo.isMain()) {
					return edo;
				}
			}
			// If no event is marked as "main" the first event is returned.
			return dido.getEvents().get(0);
		}
		return null;
	}

	protected String getMainEventFilePath(EventDataObj mainEvent) {
		String mainEventFilePath = StringUtils.EMPTY;

		if (mainEvent != null) {
			FileIdDataObj fileIdDataObj = mainEvent.getFileId();
			if (fileIdDataObj != null) {
				mainEventFilePath = fileIdDataObj.getFilePathname();
			}
		}

		return mainEventFilePath;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

	/*
	* This method constructs measures from metrics. It adds the required data to the measures, such as a URL, and then
	* saves the measures into sensorContext. This method is called by analyse().
	* */
	private void getCoverityLogoMeasures(SensorContext sensorContext, CIMClient client, ProjectDataObj covProjectObj) {
		String covProject = sensorContext.settings().getString(CoverityPlugin.COVERITY_PROJECT);
		if (covProject != null) {
			sensorContext.<String>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_PROJECT_NAME)
					.on(sensorContext.module()).withValue(covProject).save();
		}

		String ProjectUrl = createURL(client);
		if (ProjectUrl != null) {
			sensorContext.<String>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_URL_CIM_METRIC)
					.on(sensorContext.module()).withValue(ProjectUrl).save();
		}

		String ProductKey = String.valueOf(covProjectObj.getProjectKey());
		ProjectUrl = ProjectUrl + "reports.htm#p" + ProductKey;
		sensorContext.<String>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_PROJECT_URL).on(sensorContext.module())
				.withValue(ProjectUrl).save();

		sensorContext.<Integer>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_OUTSTANDING_ISSUES)
				.on(sensorContext.module()).withValue(totalDefects).save();

		sensorContext.<Integer>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_HIGH_IMPACT).on(sensorContext.module())
				.withValue(highImpactDefects).save();

		sensorContext.<Integer>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_MEDIUM_IMPACT)
				.on(sensorContext.module()).withValue(mediumImpactDefects).save();

		sensorContext.<Integer>newMeasure().forMetric(CoverityPluginMetrics.COVERITY_LOW_IMPACT).on(sensorContext.module())
				.withValue(lowImpactDefects).save();
	}

	protected ActiveRule findActiveRule(SensorContext context,
																			String domain,
																			String checkerName,
																			String subCategory,
																			String lang) {
		String key = domain + "_" + checkerName;
		RuleKey rk = CoverityUtil.getRuleKey(lang, key + "_" + subCategory);

		ActiveRule ar = context.activeRules().find(rk);

		if (ar == null && !subCategory.equals("none")) {
			rk = CoverityUtil.getRuleKey(lang, key + "_" + "none");
			ar = context.activeRules().find(rk);
		}

		if (ar == null) {
			if (domain.equals("STATIC_C")) {
				if (ar == null && checkerName.startsWith("MISRA C")) {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_C_MISRA.*");
					ar = context.activeRules().find(rk);
				} else if (ar == null && checkerName.startsWith("PW.")) {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_C_PW.*");
					ar = context.activeRules().find(rk);
				} else if (ar == null && checkerName.startsWith("SW.")) {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_C_SW.*");
					ar = context.activeRules().find(rk);
				} else if (ar == null && checkerName.startsWith("RW.")) {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_C_RW.*");
					ar = context.activeRules().find(rk);
				} else {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_C_coverity-cpp");
					ar = context.activeRules().find(rk);
				}
			} else if (domain.equals("STATIC_CS")) {
				if (ar == null && checkerName.startsWith("MSVSCA")) {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_CS_MSVSCA.*");
					ar = context.activeRules().find(rk);
				} else {
					rk = CoverityUtil.getRuleKey(lang, "STATIC_CS_coverity-cs");
					ar = context.activeRules().find(rk);
				}
			} else if (domain.equals("STATIC_JAVA")) {
				rk = CoverityUtil.getRuleKey(lang, "STATIC_JAVA_coverity-java");
				ar = context.activeRules().find(rk);
			} else if (domain.equals("OTHER") && lang.equals("js")) {
				if (ar == null && checkerName.startsWith("JSHINT")) {
					rk = CoverityUtil.getRuleKey(lang, "OTHER_JSHINT.*");
					ar = context.activeRules().find(rk);
				} else {
					rk = CoverityUtil.getRuleKey(lang, "OTHER_coverity-js");
					ar = context.activeRules().find(rk);
				}
			} else if (domain.equals("OTHER") && lang.equals("py")) {
				rk = CoverityUtil.getRuleKey(lang, "OTHER_coverity-py");
				ar = context.activeRules().find(rk);
			} else if (domain.equals("OTHER") && lang.equals("php")) {
				rk = CoverityUtil.getRuleKey(lang, "OTHER_coverity-php");
				ar = context.activeRules().find(rk);
			}
		}

		return ar;
	}

	protected InputFile findLocalFile(SensorContext context, String filePath, List<File> listOfFiles) {

		if (localInputFiles.containsKey(filePath)) {
			return localInputFiles.get(filePath);
		}

		InputFile inputFile;
		String currentDir = System.getProperty("user.dir");
		File currentDirFile = new File(currentDir);
		LOG.info("Current Directory: " + currentDir);
		String stripPrefix = context.settings().getString(CoverityPlugin.COVERITY_PREFIX);

		if (stripPrefix != null && !stripPrefix.isEmpty() && filePath.startsWith(stripPrefix)) {
			String strippedFilePath = filePath.substring(stripPrefix.length());
			filePath = new File(currentDirFile, strippedFilePath).getAbsolutePath();
			LOG.info("Full path after prefix being stripped: " + filePath);
		}

		if (platform.startsWith("Windows")) {
			filePath = filePath.replace("/", "\\");
		}

		final FileSystem fileSystem = context.fileSystem();
		inputFile = fileSystem.inputFile(fileSystem.predicates().hasPath(filePath));

		if (inputFile == null) {
			for (File possibleFile : listOfFiles) {
				if (possibleFile.getAbsolutePath().endsWith(filePath)) {
					inputFile = fileSystem.inputFile(fileSystem.predicates().hasPath(possibleFile.getAbsolutePath()));
					break;
				}
			}
		}

		return inputFile;
	}

	protected boolean validateInputFile(InputFile inputFile, String filePath, long cid) {
		if (inputFile == null) {
			LOG.info("Cannot find the file '" + filePath + "', skipping defect (CID " + cid + ")");
			return false;
		}

		if (StringUtils.isEmpty(inputFile.language())) {
			LOG.info(
					"Cannot find the language of the file '" + inputFile.absolutePath() + "', skipping defect (CID " + cid + ")");
			return false;
		}

		localInputFiles.put(filePath, inputFile);

		return true;
	}

}
