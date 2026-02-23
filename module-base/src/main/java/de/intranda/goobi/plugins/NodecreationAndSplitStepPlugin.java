package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.intranda.goobi.plugins.model.ArchiveManagementConfiguration;
import de.intranda.goobi.plugins.model.EadEntry;
import de.intranda.goobi.plugins.model.RecordGroup;
import de.intranda.goobi.plugins.model.TitleComponent;
import de.intranda.goobi.plugins.persistence.ArchiveManagementManager;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.ProcessTitleGenerator;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.enums.ManipulationType;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class NodecreationAndSplitStepPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = -4603794061508857209L;

    @Getter
    private String title = "intranda_step_nodecreation_and_split";

    @Getter
    private Step step;

    private String returnPath;

    private CreateNodeHelper cnh;
    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    @Getter
    private String pagePath = "";

    @Getter
    private PluginType type = PluginType.Step;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;
        cnh = new CreateNodeHelper(step);
        log.trace("UpdateArchiveNode step plugin initialized");
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {

        // load plugin configuration
        SubnodeConfiguration pluginConfig = ConfigPlugins.getProjectAndStepConfig(title, getStep());

        // open process
        Process sourceProcess = cnh.getProcess();
        Prefs prefs = sourceProcess.getRegelsatz().getPreferences();
        Fileformat fileformatSourceProcess = null;
        DocStruct docstructSourceProcess = null;
        try {
            fileformatSourceProcess = sourceProcess.readMetadataFile();
            docstructSourceProcess = fileformatSourceProcess.getDigitalDocument().getLogicalDocStruct();
        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        // check if node needs to be created or if it is a re-run.
        List<String> metadataToRemove = Arrays.asList(pluginConfig.getStringArray("/metadata"));
        List<String> folderToMove = Arrays.asList(pluginConfig.getStringArray("/metadata"));

        // check if cleanup metadata or folders to move still exist
        boolean dataIsPresent = checkIfMetadataIsPresent(docstructSourceProcess, metadataToRemove);

        //  foldercheck
        if (!dataIsPresent) {
            for (String foldername : folderToMove) {
                // get configured folder
                try {
                    Path currentFolder = Paths.get(sourceProcess.getConfiguredImageFolder(foldername));
                    // check if folder exists
                    if (StorageProvider.getInstance().isFileExists(currentFolder)) {
                        dataIsPresent = true;
                        break;
                    }
                } catch (IOException | SwapException | DAOException e) {
                    log.error(e);
                }
            }
        }

        if (!dataIsPresent) {
            log.info("Metadata and folder are already moved to a new process, skip node creation");
            return PluginReturnValue.FINISH;
        }

        // load archive configuration
        ArchiveManagementConfiguration archiveConfig = null;
        try {
            archiveConfig = new ArchiveManagementConfiguration();
            archiveConfig.readConfiguration(cnh.getArchiveName());
        } catch (ConfigurationException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        // get node types
        INodeType fileType = null;
        for (INodeType nodeType : archiveConfig.getConfiguredNodes()) {
            if (cnh.getNodeTypeChild().equals(nodeType.getNodeName())) {
                fileType = nodeType;
            }
        }

        // - open archive
        RecordGroup recordGroup = ArchiveManagementManager.getRecordGroupByTitle(cnh.getArchiveName());
        IEadEntry rootElement = ArchiveManagementManager.loadRecordGroup(recordGroup.getId());

        // find configured parent node
        String parentNodeId = cnh.getPluginConfig().getString("/parentNodeId[@doctype='" + docstructSourceProcess.getType().getName() + "']");
        if (StringUtils.isBlank(parentNodeId)) {
            parentNodeId = cnh.getPluginConfig().getString("/defaultParentNodeId");
        }

        Integer parentId = ArchiveManagementManager.findNodeById(cnh.getNodeIdNodeName(), parentNodeId);
        IEadEntry ancestorNode = null;

        if (parentId != null) {
            // element exist, continue with next part
            for (IEadEntry e : rootElement.getAllNodes()) {
                if (e.getDatabaseId().equals(parentId)) {
                    ancestorNode = e;
                    break;
                }
            }
        }

        if (ancestorNode == null) {
            log.error("Cannot find node with id {}", parentNodeId);
            return PluginReturnValue.ERROR;
        }

        int orderNumber = ancestorNode.getSubEntryList().size() + 1;

        // create new node as child of the ancestor element
        EadEntry entry = cnh.createNode(archiveConfig, ancestorNode, orderNumber, "");
        // set node type
        entry.setNodeType(fileType);

        // parse metadata
        cnh.parseMetadata(prefs, entry, docstructSourceProcess);

        // save new node
        ancestorNode.addSubEntry(entry);
        ancestorNode.sortElements();
        ancestorNode.updateHierarchy();
        entry.calculateFingerprint();
        ArchiveManagementManager.saveNode(recordGroup.getId(), entry);
        ArchiveManagementManager.updateNodeHierarchy(recordGroup.getId(), ancestorNode.getAllNodes());

        // load configured process template
        String processTemplateName = pluginConfig.getString("/processTemplate");
        if (StringUtils.isBlank(processTemplateName)) {
            log.error("Process template not configured");
            return PluginReturnValue.ERROR;
        }

        Process processTemplate = ProcessManager.getProcessByExactTitle(processTemplateName);
        if (processTemplate == null) {
            log.error("Configured process template does not exist");
            return PluginReturnValue.ERROR;
        }

        Fileformat nodeProcessMetadata = entry.createFileformat(prefs);
        //create new process based on the node metadata
        DigitalDocument digDoc = null;
        try {
            if (nodeProcessMetadata != null) {
                digDoc = nodeProcessMetadata.getDigitalDocument();
            }
        } catch (PreferencesException e) {
            log.error(e);
        }

        if (digDoc == null) {
            log.error("Metadata creation failed, abort");
            return PluginReturnValue.ERROR;
        }

        // generate process title via ProcessTitleGenerator
        ProcessTitleGenerator titleGenerator = prepareTitleGenerator(digDoc.getLogicalDocStruct(), archiveConfig, entry);

        // check if the generated process name is unique
        // if it is not unique, then get the full uuid version name from the generator
        // check its uniqueness again, if still not, report the failure and abort
        String processTitle = titleGenerator.generateTitle();
        if (ProcessManager.getNumberOfProcessesWithTitle(processTitle) > 0) {
            String message1 = "A process named " + processTitle + " already exists. Trying to get an alternative title.";
            log.debug(message1);
            processTitle = titleGenerator.getAlternativeTitle();
            if (ProcessManager.getNumberOfProcessesWithTitle(processTitle) > 0) {
                // title is not unique in this scenario, abort
                String message2 = "Uniqueness of the generated process name is not guaranteed, aborting.";
                log.error(message2);
                return null;
            }
        }
        log.debug("processTitle = " + processTitle);

        try {
            DocStruct physical = digDoc.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            digDoc.setPhysicalDocStruct(physical);
            Metadata imageFiles = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
            imageFiles.setValue(processTitle + "_media");
            physical.addMetadata(imageFiles);
        } catch (DocStructHasNoTypeException | UGHException e) {
            log.error(e);
        }

        // create new process based on configured process template
        Process process = new BeanHelper().createAndSaveNewProcess(processTemplate, processTitle, nodeProcessMetadata);

        // save current node
        entry.setGoobiProcessTitle(processTitle);
        ArchiveManagementManager.saveNode(recordGroup.getId(), entry);

        // delete configured metadata from original process
        try {
            Fileformat originalProcessMetadata = sourceProcess.readMetadataFile();

            metadataCleanup(originalProcessMetadata.getDigitalDocument(), metadataToRemove);
            sourceProcess.writeMetadataFile(originalProcessMetadata);
        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
        }

        // move defined folder from current process to the new process

        for (String foldername : folderToMove) {
            // get configured folder
            try {
                Path currentFolder = Paths.get(sourceProcess.getConfiguredImageFolder(foldername));
                // check if folder exists
                if (StorageProvider.getInstance().isFileExists(currentFolder)) {
                    // move folder from old process to new process
                    Path destinationFolder = Paths.get(process.getConfiguredImageFolder(foldername));
                    StorageProvider.getInstance().move(currentFolder, destinationFolder);
                }
            } catch (IOException | SwapException | DAOException e) {
                log.error(e);
            }
        }

        // finally start any open automatic tasks
        for (Step s : process.getSchritteList()) {
            if (StepStatus.OPEN.equals(s.getBearbeitungsstatusEnum()) && s.isTypAutomatisch()) {
                ScriptThreadWithoutHibernate myThread = new ScriptThreadWithoutHibernate(s);
                myThread.startOrPutToQueue();
            }
        }

        return PluginReturnValue.FINISH;
    }

    private boolean checkIfMetadataIsPresent(DocStruct docstructSourceProcess, List<String> metadataToRemove) {
        boolean dataIsPresent = false;
        if (docstructSourceProcess.getAllMetadata() != null) {
            for (Metadata md : docstructSourceProcess.getAllMetadata()) {
                if (metadataToRemove.contains(md.getType().getName())) {
                    dataIsPresent = true;
                    break;
                }
            }
        }
        if (!dataIsPresent && docstructSourceProcess.getAllPersons() != null) {
            for (Person p : docstructSourceProcess.getAllPersons()) {
                if (metadataToRemove.contains(p.getType().getName())) {
                    dataIsPresent = true;
                    break;
                }
            }
        }
        if (!dataIsPresent && docstructSourceProcess.getAllCorporates() != null) {
            for (Corporate c : docstructSourceProcess.getAllCorporates()) {
                if (metadataToRemove.contains(c.getType().getName())) {
                    dataIsPresent = true;
                    break;
                }
            }
        }
        if (!dataIsPresent && docstructSourceProcess.getAllMetadataGroups() != null) {
            for (MetadataGroup mg : docstructSourceProcess.getAllMetadataGroups()) {
                if (metadataToRemove.contains(mg.getType().getName())) {
                    dataIsPresent = true;
                    break;
                }
            }
        }
        return dataIsPresent;
    }

    private void metadataCleanup(DigitalDocument digDoc, List<String> metadataToRemove) {
        if (!metadataToRemove.isEmpty()) {
            DocStruct logical = digDoc.getLogicalDocStruct();
            List<Metadata> metadataList = new ArrayList<>();
            List<Person> personList = new ArrayList<>();
            List<Corporate> corpList = new ArrayList<>();
            List<MetadataGroup> groupList = new ArrayList<>();
            if (logical.getAllMetadata() != null) {
                for (Metadata md : logical.getAllMetadata()) {
                    if (metadataToRemove.contains(md.getType().getName())) {
                        metadataList.add(md);
                    }
                }
            }
            if (logical.getAllPersons() != null) {
                for (Person p : logical.getAllPersons()) {
                    if (metadataToRemove.contains(p.getType().getName())) {
                        personList.add(p);
                    }
                }
            }
            if (logical.getAllCorporates() != null) {
                for (Corporate c : logical.getAllCorporates()) {
                    if (metadataToRemove.contains(c.getType().getName())) {
                        corpList.add(c);
                    }
                }
            }
            if (logical.getAllMetadataGroups() != null) {
                for (MetadataGroup mg : logical.getAllMetadataGroups()) {
                    if (metadataToRemove.contains(mg.getType().getName())) {
                        groupList.add(mg);
                    }
                }
            }

            for (Metadata md : metadataList) {
                logical.removeMetadata(md, true);
            }
            for (Person p : personList) {
                logical.removePerson(p, true);
            }
            for (Corporate c : corpList) {
                logical.removeCorporate(c, true);
            }
            for (MetadataGroup mg : groupList) {
                logical.removeMetadataGroup(mg, true);
            }
        }
    }

    private ProcessTitleGenerator prepareTitleGenerator(DocStruct docstruct, ArchiveManagementConfiguration config, IEadEntry entry) {

        ProcessTitleGenerator titleGenerator = new ProcessTitleGenerator();
        titleGenerator.setSeparator(config.getSeparator());
        titleGenerator.setBodyTokenLengthLimit(config.getLengthLimit());
        for (TitleComponent comp : config.getTitleParts()) {
            // set title type
            ManipulationType manipulationType = null;
            // check for special types
            switch (comp.getType().toLowerCase()) {
                case "camelcase", "camel_case", "camelcaselenghtlimited", "camel_case_lenght_limited":
                    if (config.getLengthLimit() > 0) {
                        manipulationType = ManipulationType.CAMEL_CASE_LENGTH_LIMITED;
                    } else {
                        manipulationType = ManipulationType.CAMEL_CASE;
                    }

                    break;
                case "afterlastseparator", "after_last_separator":
                    manipulationType = ManipulationType.AFTER_LAST_SEPARATOR;
                    break;
                case "beforefirstseparator", "before_first_separator":
                    manipulationType = ManipulationType.BEFORE_FIRST_SEPARATOR;
                    break;
                case "normal":
                default:
                    manipulationType = ManipulationType.NORMAL;
            }

            // get actual value
            String val = null;
            if (StringUtils.isNotBlank(comp.getValue())) {
                // static text
                val = comp.getValue();
            } else {
                // get value from metadata
                String metadataName = comp.getName();

                for (Metadata md : docstruct.getAllMetadata()) {
                    if (md.getType().getName().equals(metadataName)) {
                        val = md.getValue();
                        break;
                    }
                }
            }
            if (StringUtils.isNotBlank(val)) {
                titleGenerator.addToken(val, manipulationType);
            }
        }

        if (StringUtils.isBlank(titleGenerator.generateTitle())) {
            // default title, if nothing is configured
            IEadEntry idEntry = config.isUseIdFromParent() ? entry.getParentNode() : entry;
            String valueOfFirstToken = idEntry.getId();
            titleGenerator.addToken(valueOfFirstToken, ManipulationType.BEFORE_FIRST_SEPARATOR);

            ManipulationType labelTokenType = config.getLengthLimit() > 0 ? ManipulationType.CAMEL_CASE_LENGTH_LIMITED : ManipulationType.CAMEL_CASE;
            String label = entry.getLabel();
            titleGenerator.addToken(label, labelTokenType);
        }
        return titleGenerator;
    }

}
