package com.liferay.damascus.cli;

import com.beust.jcommander.*;
import com.google.common.collect.*;
import com.liferay.damascus.cli.common.*;
import com.liferay.damascus.cli.exception.*;
import com.liferay.damascus.cli.json.*;
import com.liferay.damascus.cli.json.DamascusBase;
import freemarker.template.*;
import lombok.*;
import lombok.extern.slf4j.*;
import org.apache.commons.configuration2.ex.*;
import org.apache.commons.io.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Create Service
 * <p>
 * Damascus will create service according to the base.json
 *
 * @author Yasuyuki Takeo
 * @author Sébastien Le Marchand
 */
@Slf4j
@Data
public class CreateCommand implements ICommand {

    public CreateCommand() {
    }

    /**
     * Runnable validation
     *
     * @return true if this command can be invoked
     */
    public boolean isRunnable() {
        return isCreate();
    }

    /**
     * Generate Scaffolding from a template.
     *
     * @param damascusBase     Damascus object
     * @param templateFileName target template name (e.g. Portlet_XXXXLocalService.ftl)
     * @param outputFilePath   Output file path. if it's null, refers output file path from createPath in the template
     * @param app              Application object.
     * @throws IOException
     * @throws URISyntaxException
     * @throws TemplateException
     * @throws ConfigurationException settings.properties file manipulation error
     */
    private void generateScaffolding(DamascusBase damascusBase, String templateFileName, String outputFilePath, Application app)
        throws IOException, URISyntaxException, TemplateException, ConfigurationException {
        Map params = Maps.newHashMap();

        //Mapping values used in templates
        params.put(DamascusProps.BASE_DAMASCUS_OBJ, damascusBase);
        params.put(DamascusProps.BASE_TEMPLATE_UTIL_OBJ, TemplateUtil.getInstance());
        params.put(DamascusProps.BASE_CASE_UTIL_OBJ, CaseUtil.getInstance());
        params.put(DamascusProps.BASE_CURRENT_APPLICATION, app);
        params.put(DamascusProps.TEMPVALUE_FILEPATH, CREATE_TARGET_PATH);
        String author = PropertyUtil.getInstance().getProperty(DamascusProps.PROP_AUTHOR);
        params.put(DamascusProps.PROP_AUTHOR.replace(".","_"),author);

        //Parse template and output
        TemplateUtil.getInstance().process(CreateCommand.class, damascusBase.getLiferayVersion(), templateFileName, params, outputFilePath);
    }

    /**
     * Generate product skelton
     * <p>
     * Generating service and web project skeleton at once.
     *
     * @param projectName    Project Name
     * @param packageName    Package Name
     * @param destinationDir Destination dir where the project is created.
     * @throws Exception
     */
    private void generateProjectSkeleton(String projectName, String packageName, String destinationDir) throws Exception {

        //Generate Service (*-service, *-api) skelton
        CommonUtil.createServiceBuilderProject(
            projectName,
            packageName,
            destinationDir
        );

        //Generate Web project (*-web)
        CommonUtil.createMVCPortletProject(
            projectName,
            packageName,
            destinationDir + DamascusProps.DS + projectName
        );
    }

    /**
     * Move Project into current.
     * <p>
     * Liferay template library doesn't allow to overwrite project file,
     * So it gets nested in this tool. This method move those nested project directory
     * into the current directory.
     *
     * @param projectName Project name
     * @throws IOException
     */
    private void moveProjectsToCurrentDir(String projectName) throws IOException {

        //Generated Project files are nested. Move into current directory
        File srcDir  = new File("." + DamascusProps.DS + projectName);
        File distDir = new File("." + DamascusProps.DS);

        if (!srcDir.exists() || !srcDir.isDirectory()) {
            return;
        }

        //Set execute permission to gradlew and gradlew.bat
        FileUtils.copyDirectory(srcDir, distDir);
        FileUtils.deleteDirectory(srcDir);

        File gradlew = new File("." + DamascusProps.DS + DamascusProps._GRADLEW_UNIX_FILE_NAME);

        if (gradlew.exists()) {
            gradlew.setExecutable(true);
        }

        File gradlewBat = new File("." + DamascusProps.DS + DamascusProps._GRADLEW_WINDOWS_FILE_NAME);

        if (gradlew.exists()) {
            gradlew.setExecutable(true);
        }
    }

    /**
     * Execute create command
     *
     * @param damascus
     * @param args
     */
    @Override
    public void run(Damascus damascus, String... args) {
        try {

            System.out.println("Started creating service scaffolding. Fetching base.json");

            // Mapping base.json into an object after parsing values
            DamascusBase dmsb = JsonUtil.getObject(
                CREATE_TARGET_PATH + DamascusProps.BASE_JSON,
                DamascusBase.class
            );

            //Get root path to the templates
            File resourceRoot = TemplateUtil
                .getInstance()
                .getResourceRootPath(dmsb.getLiferayVersion());

            //Fetch all template file paths
            Collection<File> templatePaths = TemplateUtil
                .getInstance()
                .getTargetTemplates(DamascusProps.TARGET_TEMPLATE_PREFIX, resourceRoot);

            String camelCaseProjectName = dmsb.getProjectName();
            
			String dashCaseProjectName = CaseUtil.getInstance().camelCaseToDashCase(camelCaseProjectName);
            
            //1. Generate skeleton of the project.
            //2. Parse service.xml
            //3. run gradle buildService
            //4. generate corresponding files from templates
            //5. run gradle buildService again.

            // Get path to the service.xml
            String serviceXmlPath = TemplateUtil.getInstance().getServiceXmlPath(
                CREATE_TARGET_PATH,
                dashCaseProjectName
            );

            System.out.println("Generating *-api, *-service, *-web skeletons for " + dashCaseProjectName);

            // Generate skeletons of the project
            generateProjectSkeleton(
            	dashCaseProjectName,
                dmsb.getPackageName(),
                CREATE_TARGET_PATH
            );

            System.out.println("Parsing " + serviceXmlPath);

            // Generate service.xml based on base.json configurations and overwrite existing service.xml
            generateScaffolding(dmsb, DamascusProps.SERVICE_XML, serviceXmlPath, null);

            System.out.println("Running \"gradle buildService\" to generate the service based on parsed service.xml");

            //run "gradle buildService" to generate the skeleton of services.
            CommonUtil.runGradle(serviceXmlPath, "buildService");

			
            //Parse all templates and generate scaffold files.
            for (Application app : dmsb.getApplications()) {

                System.out.print("Parsing templates");

                //Process all templates
                for (File template : templatePaths) {
                    System.out.print(".");
                    generateScaffolding(dmsb, template.getName(), null, app);
                }

                System.out.println(".");
            }
            
            System.out.println("Running \"gradle buildService\" to regenerate the service with scaffolding files.");

            //run "gradle buildService" to regenerate with added templates
            CommonUtil.runGradle(serviceXmlPath, "buildService");

            System.out.println("Moving all modules projects into the same directory");

            //Finalize Project Directory: move modules directories into the current directory
			moveProjectsToCurrentDir(dashCaseProjectName);

            System.out.println("Done.");

        } catch (DamascusProcessException e) {
            // Damascus operation error
            log.error(e.getMessage());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final String CREATE_TARGET_PATH = "." + DamascusProps.DS;

    /**
     * Command Parameters
     */
    @Parameter(names = "-create", description = "Create service according to base.json.")
    private boolean create = false;

}
