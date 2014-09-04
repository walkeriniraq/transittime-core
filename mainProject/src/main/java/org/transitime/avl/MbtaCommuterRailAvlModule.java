/*
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */

package org.transitime.avl;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.config.StringConfigValue;
import org.transitime.db.structs.AvlReport;
import org.transitime.db.structs.AvlReport.AssignmentType;
import org.transitime.modules.Module;

/**
 *
 *
 * @author SkiBu Smith
 *
 */
public class MbtaCommuterRailAvlModule extends AvlModule {

	// If debugging feed and want to not actually process
	// AVL reports to generate predictions and such then
	// set shouldProcessAvl to false;
	private static boolean shouldProcessAvl = true;
	
	private static final Logger logger = LoggerFactory
			.getLogger(MbtaCommuterRailAvlModule.class);

	/********************** Member Functions **************************/
	
	private static StringConfigValue mbtaCommuterRailFeedUrl = 
			new StringConfigValue("transitime.avl.mbtaCommuterRailFeedUrl", 
					"http://developer.mbta.com/lib/GTRTFS/AVLData.txt",
					"The URL of the MBTA commuter rail feed to use.");
	private static String getMbtaCommuterRailFeedUrl() {
		return mbtaCommuterRailFeedUrl.getValue();
	}

	/**
	 * Simple constructor
	 * 
	 * @param agencyId
	 */
	public MbtaCommuterRailAvlModule(String agencyId) {
		super(agencyId);
	}

	/* (non-Javadoc)
	 * @see org.transitime.avl.AvlModule#getUrl()
	 */
	@Override
	protected String getUrl() {
		return getMbtaCommuterRailFeedUrl();
	}

	/* (non-Javadoc)
	 * @see org.transitime.avl.AvlModule#processData(java.io.InputStream)
	 */
	@Override
	protected void processData(InputStream in) throws Exception {
		// Map, keyed on vehicle ID, is for keeping track of the last AVL
		// report for each vehicle. Since GPS reports are in chronological
		// order in the feed the element in the map represents the last
		// AVL report.
		Map<String, AvlReport> avlReports = new HashMap<String, AvlReport>();
		
		// Read in all AVL reports and add them to the avlReports map
		BufferedReader reader = new BufferedReader(new InputStreamReader(in));
		String line;
		while ((line = reader.readLine()) != null) {
			// Log each line
			logger.info(line);
			
			AvlReport avlReport = parseAvlReport(line);
			if (avlReport != null) {
				System.out.println("line=" + line);
				System.out.println(avlReport);
				
				avlReports.put(avlReport.getVehicleId(), avlReport);
			}
		}
		
		// Process the last AVL report for each vehicle
		if (shouldProcessAvl) {
			for (AvlReport avlReport : avlReports.values()) {
				processAvlReport(avlReport);
			}
		}
	}
	
	// The following are determining the proper place in the
	// AVL string to process for the particular piece of data
	private static String vehicleIdMarker = "Vehicle ID:";
	private static String locationMarker = "Location[";
	private static String workpieceMarker = "Workpiece:";
	private static String patternMarker = "Pattern:";
	private static String gpsMarker = "GPS:";
	
	/**
	 * Reads line from AVL feed and creates and returns corresponding AVL
	 * report.
	 * 
	 * @param line
	 *            Line from AVL feed that represents a single AVL report.
	 * @return AvlReport for the line, or null if there is a problem parsing the
	 *         data
	 */
	private AvlReport parseAvlReport(String line) {
		// If line is not valid then return null
		if (line.isEmpty())
			return null;
		if (!line.contains(vehicleIdMarker) || !line.contains(locationMarker))
			return null;
		
		// Get vehicle ID
		String vehicleId = getValue(line, vehicleIdMarker + "(\\d+)");
		
		// Get the vehicle location
		String workpiece = getValue(line, workpieceMarker + "(\\d+)");
		String pattern = getValue(line, patternMarker + "(\\d+)");
		
		// Get GPS data from TAIP formatted string
		String gpsTaipStr = getValue(line, gpsMarker + "(\\>.+\\<)");
		TaipGpsLocation taipGpsLoc = TaipGpsLocation.get(gpsTaipStr);
		if (taipGpsLoc == null) {
			logger.error("Could not parse TAIP string \"{}\" for line {}", 
					gpsTaipStr, line);
			return null;
		}
		long gpsTime = taipGpsLoc.getFixEpochTime();
		double lat = taipGpsLoc.getLatitude();
		double lon = taipGpsLoc.getLongitude();
		float heading = taipGpsLoc.getHeading();
		float speed = taipGpsLoc.getSpeedMetersPerSecond();
		
		AvlReport avlReport = new AvlReport(vehicleId, gpsTime, lat, lon,
				speed, heading);
		avlReport.setAssignment(pattern, AssignmentType.BLOCK_ID);
		avlReport.setField1("workpiece", workpiece);
		return avlReport;
	}
	
	/**
	 * Gets a value from the string using specified regular expression.
	 * The regular expression should indicate what is before the value
	 * plus a capture element to indicate the actual value. So for
	 * a string containing something like Value:123 the regEx should
	 * be "Value:{\\d+}".
	 * 
	 * @param str
	 * @param regEx
	 * @return
	 */
	private static String getValue(String str, String regEx) {
		Pattern pattern = Pattern.compile(regEx);
		Matcher matcher = pattern.matcher(str);
		
		boolean found = matcher.find();
		if (!found)
			return null;
		
		String result = str.substring(matcher.start(1), matcher.end(1));
		return result;
	}
	
	/**
	 * Just for debugging
	 */
	public static void main(String[] args) {
		// For debugging turn off the actual processing of the AVL data.
		// This way the AVL data is logged, but that is all.
		shouldProcessAvl = false;
		
		// Create a NextBusAvlModue for testing
		Module.start("org.transitime.avl.MbtaCommuterRailAvlModule");
	}

}
