package org.dtk.resources.build;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.dtk.resources.build.manager.BuildStatusManager;
import org.dtk.resources.exceptions.IncorrectParameterException;
import org.dtk.resources.packages.PackageRepository;
import org.dtk.util.JsonUtil;

/**
 * Class holding full details of a build request. Also provides
 * utility methods to transform build details into correct format
 * to ease processing in Rhino-JS environment. All build requests
 * are identified by a unique reference, calculated as a hash of 
 * variable parameters.
 * 
 * @author James Thomas
 */

public class BuildRequest {
	/** User provided build details */
	List<Map<String, String>> packages;
	String cdn;
	String optimise;
	String cssOptimise;
	String platforms;
	String themes;
	List<Map<String, Object>> layers;
	
	/** Unique identifier for these build parameters, used to cache computed result. */
	String buildReference;

	/** File path format for cached build results, base_dir/build_id/dojo.zip */
	//protected static final String cachedBuildFileFormat = "%1$s/dojo.zip";
	
	protected static final String archivedBuildFile = "dojo.zip";
	
	/** Formatter string for toString() output **/
	protected static final String format = "org.dtk.resources.build.BuildRequest: packages=%1$s " +
		"cdn=%2$s, optimise=%3$s, cssOptimise=%4$s, platforms=%5$s, themes=%6$s, layers=%7$s";
	
	/** Dojo build profile format */
	protected static final String profileFormat = "dependencies = %1$s;";
	
	/**
	 * Create a new build request from constructor parameters.
	 * 
	 * @param packages - Modules reference these packages
	 * @param cdn - Content Delivery Network
	 * @param optimise - Optimisation level
	 * @param layers - Build layers 
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 */
	public BuildRequest(List<Map<String, String>> packages, String cdn, String optimise, String cssOptimise, String platforms,
		String themes, List<Map<String, Object>> layers) 
		throws JsonParseException, JsonMappingException, NoSuchAlgorithmException, IOException {
		
		this.packages = packages;
		this.cdn = cdn;
		this.optimise = optimise;
		this.cssOptimise = cssOptimise;
		this.platforms = platforms;
		this.themes = themes; 
		this.layers = layers;
		
		// Generate unique build reference for this set of 
		// parameters, used hash digest of parameters.
		this.buildReference = generateBuildDigest();
	}
	
	/**
	 * Generate dojo build profile for this build request. Will contain
	 * all the relevant parameters to pass to the build system. 
	 * 
	 * @return Dojo build profile for this request
	 * @throws IOException - Unable to render build profile
	 * @throws JsonMappingException - Unable to map from Java objects to JSON
	 * @throws JsonParseException - Illegal JSON parsing error
	 */
	public String getProfileText() throws JsonParseException, JsonMappingException, IOException {
		Map<String, Object> buildProfile = new HashMap<String, Object>();
		
		buildProfile.put("layers", getProfileLayers());
		buildProfile.put("layerOptimize", optimise);
		buildProfile.put("prefixes", getModulePrefixes());
		buildProfile.put("cssOptimize", cssOptimise);
		
		// How do we do platform?????
		// How do we do CDN????
		
		String profileText = String.format(profileFormat, JsonUtil.writeJavaToJson(buildProfile));
		
		return profileText;
	}
	
	protected List<Map<String, Object>> getProfileLayers() {
		List<Map<String, Object>> profileLayers = new ArrayList<Map<String, Object>>();
		
		Iterator<Map<String, Object>> layerIter = layers.iterator();
		
		while(layerIter.hasNext()) {
			final Map<String, Object> layer = layerIter.next();			
			final List<String> dependencies = new ArrayList<String>();
			
			Iterator<Map<String, String>> modulesIter = ((List<Map<String, String>>) layer.get("modules")).iterator();
			
			while(modulesIter.hasNext()) {
				dependencies.add(modulesIter.next().get("name"));
			}
						
			profileLayers.add(new HashMap<String, Object>() {{
				put("dependencies", dependencies);
				put("name", layer.get("name"));
			}});
		};
		
		return profileLayers;
	}
	
	
	/**
	 * Generate the unique digest for this build request. Used to identify the same build job
	 * between requests. Variable parameters used to control the build are hashed using the SHA-1
	 * algorithm. 
	 * 
	 * @return Build digest reference
	 * @throws JsonParseException - Error parsing layers to Json
	 * @throws JsonMappingException - Error parsing layers to Json
	 * @throws IOException - Error parsing layers to Json
	 * @throws NoSuchAlgorithmException - Unable to access SHA-1 algorithm
	 */
	protected String generateBuildDigest() 
	throws JsonParseException, JsonMappingException, IOException, NoSuchAlgorithmException {
		// TODO: Sort module list, so that we don't compile same thing twice
		// when modules are included in a different order.

		// Convert host object to simple JSON representation. Simple 
		// reliable text representation of java object state.
		String layersJson = JsonUtil.writeJavaToJson(layers),
			packagesJson = JsonUtil.writeJavaToJson(packages);

		// Get instance of hashing algorithm and update digest with 
		// build parameters.
		MessageDigest md = MessageDigest.getInstance("SHA");
		md.update(layersJson.getBytes());
		md.update(packagesJson.getBytes());
		md.update(cdn.getBytes());
		md.update(themes.getBytes());
		md.update(optimise.getBytes());
		md.update(cssOptimise.getBytes());
		md.update(platforms.getBytes());

		// Generate BASE64 encoded result, replacing non-safe directory characters
		String optionsDigest = (new String( (new Base64()).encode(md.digest())));
		String digest = optionsDigest.replace('+', '~').replace('/', '_').replace('=', '_');

		return digest;
	}
	
	/**
	 * Convert build layer details from user request, use native object
	 * rather than generic map as this is easy to process in JavaScript 
	 * context.
	 * 
	 * @param layerDetails - Build details from user request
	 * @return Array of layer objects, corresponding to build layers 
	 */
	public Layer[] getBuildLayersArray()
	throws IncorrectParameterException {
		int numOfLayers = this.layers.size();
		Layer[] buildLayers = new Layer[numOfLayers];

		// Loop through requests, converting each JSON object parameter.
		for(int idx = 0; idx < numOfLayers; idx++) {
			buildLayers[idx] = new Layer(layers.get(idx));
		}

		return buildLayers;
	}
	
	/**
	 * Given a build reference, find the associated file path for result of
	 * the build. Constructed from full build result cache directory and
	 * unique build reference.
	 * 
	 * @param buildDigest - Reference build identifier
	 * @return String - Absolute file path for reference build id 
	 */
	public String getBuildResultPath() {
		// Generate the full file cache path from cache directory, build id and build result file
		File buildResultFile = new File(getBuildResultDir(), archivedBuildFile);
		
		return buildResultFile.getAbsolutePath();
	}
	
	/**
	 * Return the directory path which will contain the build aritfacts for this 
	 * unique build request. Constructed from the build cache directory alongside 
	 * the custom build identifier.
	 * 
	 * @return Directory containing build artifacts, archive, profile and files.
	 */
	public String getBuildResultDir() {
		BuildStatusManager buildStatusManager = BuildStatusManager.getInstance();
		String buildCacheRepository = buildStatusManager.getBuildResultCachePath();
		
		// Add unique build identifier to the cache path location
		File buildResultDir = new File(buildCacheRepository, buildReference);
		
		return buildResultDir.getAbsolutePath();
	}
	
	public List<File> getGeneratedLayerFiles() {
		ArrayList<File> layerLocations = new ArrayList<File>();
		String layerArtifactLocations = "dojo/dojo";
		String buildResultDir = getBuildResultDir();
		File baseLayerLocation = new File(buildResultDir, layerArtifactLocations);
		
		Iterator<Map<String, Object>> layerIter = layers.iterator();
		
		while(layerIter.hasNext()) {
			String layerName = (String) layerIter.next().get("name");			
			layerLocations.add(new File(baseLayerLocation, layerName));
		}
		
		return layerLocations;
	}
	
	public List<List<String>> getModulePrefixes() {
		PackageRepository packageRepo = PackageRepository.getInstance();
		List<List<String>> modulePrefixLocations = new ArrayList<List<String>>();
		Set<String> modulePrefixes = new HashSet<String>();
		
		// Create custom module lookup, used to match module prefixes with a 
		// package location
		Map<String, String> packageLocationLookup = new HashMap<String, String>();		
		Iterator<Map<String, String>> iter = packages.iterator();		
		while(iter.hasNext()) {
			Map<String, String> referencedPackage = iter.next();
			
			String name = referencedPackage.get("name"), 
				version = referencedPackage.get("version");
			
			packageLocationLookup.put(name, packageRepo.getPackageLocation(name, version));			
		}

		// Search through all module dependencies, creating location references for
		// all module prefixes
		Iterator<Map<String, Object>> layerIter = layers.iterator();
		while(layerIter.hasNext()) {
			Map<String, Object> layer = layerIter.next();
			List<Map<String, String>> layerModules = (List<Map<String, String>>) layer.get("modules");
			Iterator<Map<String, String>> modulesIter = layerModules.iterator();
			while (modulesIter.hasNext()) {
				Map<String, String> details = modulesIter.next();
				String moduleName = details.get("name");
				String modulePrefix = moduleName.split("\\.")[0];
				// If we haven't already resolved location for this prefix, ignoring DTK modules
				if (!modulePrefixes.contains(modulePrefix)) {
					String location = packageLocationLookup.get(details.get("package"));
					modulePrefixLocations.add(Arrays.asList(modulePrefix, (new File(location, modulePrefix)).getAbsolutePath()));
					modulePrefixes.add(modulePrefix);
				}
			}
		}
		
		return modulePrefixLocations;
	}
	

	// Getter and setters...
	public String getBuildReference() {
		return buildReference;
	}
	
	public String getDojoLocation() {
		PackageRepository packageRepo = PackageRepository.getInstance();
		return packageRepo.getPackageLocation("dojo", getDojoVersion());
	}
	
	public String getDojoVersion() {
		return getDojoPackage().get("version");
	}
	
	protected Map<String, String> getDojoPackage() {
		Iterator<Map<String, String>> iter = packages.iterator();
		
		while(iter.hasNext()) {
			Map<String, String> referencedPackage = iter.next();
			if ("dojo".equals(referencedPackage.get("name"))) {
				return referencedPackage;
			}
		}
		
		return null;
	}

	public String getCdn() {
		return cdn;
	}

	public String getOptimise() {
		return optimise;
	}

	public String getCssOptimise() {
		return cssOptimise;
	}
	
	public String getPlatforms() {
		return platforms;
	}
	
	public String getThemes() {
		return themes;
	}
	
	public List<Map<String, Object>> getLayers() {
		return layers;
	}
	
	/** 
	 * Return human-readable string representation of this 
	 * object and its internal members. 
	 * 
	 * @throws IOException - Error mapping layers to JSON
	 * @throws JsonMappingException - Error mapping layers to JSON
	 * @throws JsonParseException - Error mapping layers to JSON
	 */
	public String serialise() throws JsonParseException, JsonMappingException, IOException  {
		return String.format(format, JsonUtil.writeJavaToJson(packages), cdn, optimise, cssOptimise, 
			platforms, themes, JsonUtil.writeJavaToJson(layers));
	}
}
