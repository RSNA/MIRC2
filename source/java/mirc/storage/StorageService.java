/*---------------------------------------------------------------
*  Copyright 2005 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense)
*----------------------------------------------------------------*/

package mirc.storage;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

import mirc.MircConfig;
import mirc.prefs.Preferences;
import mirc.ssadmin.StorageServiceAdmin;

import org.apache.log4j.Logger;

import org.rsna.servlets.Servlet;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.server.Authenticator;
import org.rsna.server.Path;
import org.rsna.server.Users;
import org.rsna.server.User;

import org.rsna.ctp.objects.DicomObject;

import org.rsna.util.FileUtil;
import org.rsna.util.HttpUtil;
import org.rsna.util.SerializerUtil;
import org.rsna.util.StringUtil;
import org.rsna.util.XmlUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The MIRC Storage Service servlet.
 * The Storage Service provides indexing of the documents it stores
 * and responds to queries from Query Services.
 */
public class StorageService extends Servlet {

	static final Logger logger = Logger.getLogger(StorageService.class);

	/**
	 * Construct a StorageService.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public StorageService(File root, String context) {
		super(root, context);
		//Override the root supplied by the HttpServer
		//with the root of the MIRC plugin.
		this.root = MircConfig.getInstance().getRootDirectory();
	}

	/**
	 * The servlet method that responds to an HTTP GET.
	 * This method interprets the path and query parameters ang returns
	 * the requested resource. It handles .xml and .dcm files specially.
	 * All other file types are served normally.
	 * @param req The HttpRequest provided by the servlet container.
	 * @param res The HttpResponse provided by the servlet container.
	 */
	public void doGet(HttpRequest req, HttpResponse res ) throws Exception {

		String reqpath = req.getPath();
		String reqpathLC = reqpath.toLowerCase();

		//Handle function requests first
		Path path = req.getParsedPath();
		String function = path.element(1).toLowerCase();
		if (function.equals("delete")) {

			//Delete the document
			boolean ok = false;
			try {
				if (path.length() > 3) {
					File docFile = new File( root, "storage");
					docFile = new File(docFile, path.subpath(2).substring(1).replace("/", File.separator) );
					Document doc = XmlUtil.getDocument(docFile);
					if (userIsAuthorizedTo( "delete", doc, req )) {
						String ssid = path.element(2);
						String docref = path.subpath(3).substring(1);
						ok = StorageServiceAdmin.deleteDocument( ssid, docref );
					}
				}
			}
			catch (Exception cannotDelete) { }
			res.disableCaching();
			res.setContentType("xml");
			res.write( (ok ? "<ok/>" : "<notok/>") );
			if (!ok) res.setResponseCode( res.notfound );
			res.send();
			return;
		}

		//Okay, it wasn't a function call; service the file request.
		//Pass non-XML and non-DCM files to the superclass.
		if (!reqpathLC.endsWith(".xml") && !reqpathLC.endsWith("dcm")) {
			super.doGet(req, res);
			return;
		}

		//Get the file and bail out if it doesn't exist.
		File file = new File(root, req.getPath());
		if (!file.exists()) {
			res.setResponseCode( res.notfound );
			res.send();
			return;
		}

		//Handle DCM files
		if (reqpathLC.endsWith(".dcm")) {
			if (req.getParameter("dicom") != null) {
				//This is a request for an element listing page.
				res.disableCaching();
				try {
					DicomObject dob = new DicomObject(file);
					res.write( dob.getElementTablePage( req.userHasRole("admin") ) );
					res.setContentType("html");
				}
				catch (Exception ex) { res.setResponseCode( res.notfound ); }
			}
			else if (req.getParameter("bi") != null) {
				//This is a request for a java.awt.image.BufferedImage
				try {
					int frame = StringUtil.getInt( req.getParameter("frame"), 0);
					int max = StringUtil.getInt( req.getParameter("maxWidth"), Integer.MAX_VALUE);
					int min = StringUtil.getInt( req.getParameter("minWidth"), 96);
					DicomObject dob = new DicomObject(file);
					BufferedImage img = dob.getScaledBufferedImage(frame, max, min);
					if (img != null) {
						byte[] bytes = SerializerUtil.serialize(img);
						res.write(bytes);
					}
					//Note 1: If it isn't an image, we return zero bytes, instead of notfound.
					//Note 2: We set a special content type to indicate this is a java object.
					res.setHeader("Content-Type", "application/java.awt.image.BufferedImage");
				}
				catch (Exception ex) { res.setResponseCode( res.notfound ); }
			}
			else {
				//This is a request to download the DICOM file.
				res.write(file);
				res.setContentType("dcm");
				res.setContentDisposition(file);
			}
			res.send();
			return;
		}

		//Handle XML files
		else {
			//See if the file parses
			Document doc;
			try { doc = XmlUtil.getDocument(file); }
			catch (Exception ex) {
				//The xml file doesn't parse. Send the text of the document
				//as XML so the browser will show the user where it failed.
				res.write(file);
				res.setContentType("xml");
				res.disableCaching();
				res.send();
				return;
			}

			//OK, we now have a real XML document. See if it is a MIRCdocument.
			Element rootElement = doc.getDocumentElement();
			if (!rootElement.getTagName().equals("MIRCdocument")) {
				//No, just return the file.
				res.write(file);
				res.setContentType("xml");
				res.disableCaching();
				res.send();
				return;
			}

			//It's a MIRCdocument. See if this is a zip export request.
			String zipParameter = req.getParameter("zip");
			if (zipParameter != null) {

				//Check whether export is authorized.
				if (userIsAuthorizedTo("export", doc, req)) {

					//Export is authorized; make the file for the zip file
					String ext = ".zip";
					String extParameter = req.getParameter("ext", "").trim();
					if (!extParameter.equals("")) ext = "." + extParameter;
					String name = file.getName();
					name = name.substring(0, name.length()-4) + ext;
					File zipFile = new File(file.getParentFile(), name);

					//Insert the path attribute (if necessary) for third party author tools.
					//The path attribute starts with the ssid, as in: "ss1/docs/...".
					String newPathAttr = req.getParsedPath().subpath(1).substring(1);
					String oldPathAttr = rootElement.getAttribute("path");
					if (!newPathAttr.equals(oldPathAttr)) {
						rootElement.setAttribute("path", newPathAttr);
						FileUtil.setText(file, XmlUtil.toString(doc));
					}

					//Now zip the case.
					String[] filenames = getFilenames(doc, file);
					boolean ok = FileUtil.zipFiles(filenames, file.getParentFile(), zipFile);

					String myrsnaParameter = req.getParameter("myrsna");
					String destParameter = req.getParameter("dest");

					if (ok) {
						if ((destParameter == null) && (myrsnaParameter == null)) {
							res.write(zipFile);
							res.setContentType("zip");
							res.setContentDisposition(zipFile);
						}
						else if (myrsnaParameter != null) {
							String myRsnaResult = "The zip file was stored successfully.";
							if (!exportToMyRsna(req.getUser(), zipFile)) {
								myRsnaResult = "The zip file could not be stored.";
							}
							res.write( myRsnaResult );
							res.setContentType("txt");
						}
						else {
							res.write( exportToDestination(req.getUser(), zipFile, destParameter) );
							res.setContentType("txt");
						}
					}
					else {
						res.write( "<html><head><title>ZipException</title></head>" );
						res.write( "<body><h3>Server Exception</h3><p>Unable to create the zip file.</p></body></html>" );
						res.setContentType("html");
					}
					//NOTE: Do not disable caching; otherwise, the download will
					//fail because the browser won't be able to store the file;
					res.send();
					zipFile.delete();
					AccessLog.logAccess(req, doc);
					return;
				}
				else {
					//Export is not authorized.
					res.setResponseCode( res.forbidden );
					res.send();
					return;
				}
			}

			//It's not a zip export, check whether read is authorized.
			if (!userIsAuthorizedTo("read", doc, req)) {
				String qs = req.getQueryString();
				if (!qs.equals("")) qs = "?" + qs;
				String url = req.getPath() + qs;
				try { url = URLEncoder.encode(url, "UTF-8"); }
				catch (Exception ignore) { }
				String ssid = path.element(1);
				res.redirect( "/challenge?url="+url+"&ssid="+ssid );
				res.send();
				return;
			}

			//Read is authorized. See if the user wants to suppress
			//transformation and just get the original XML. This is only
			//allowed if the user is authorized to export the document.
			if ((req.getParameter("xsl") != null) && userIsAuthorizedTo("export", doc, req)) {
				res.write(file);
				res.setContentType("xml");
				res.disableCaching();
				res.send();
				AccessLog.logAccess(req, doc);
				return;
			}

			//OK, transform the document and return the result
			String xslResource = "/storage/MIRCdocument.xsl";
			File xslFile = new File( file.getParentFile(), "MIRCdocument.xsl" );
			Document xsl = XmlUtil.getDocument( FileUtil.getStream( xslFile, xslResource ) );
			Object[] params = getParams( req, doc );
			res.write( XmlUtil.getTransformedText( doc, xsl, params ) );
			res.setContentType("html");
			res.send();
			AccessLog.logAccess(req, doc);
		}
	}

	/**
	 * The servlet method that responds to an HTTP POST.
	 * This method interprets the parameters as a query generated by the
	 * Query Service, uses it to search the index, and returns a MIRCqueryresult.
	 * @param req The HttpRequest provided by the servlet container.
	 * @param res The HttpResponse provided by the servlet container.
	 */
	public void doPost(HttpRequest req, HttpResponse res ) throws Exception {

		long currentTime = System.currentTimeMillis();
		logger.debug("Query received for "+req.path);

		//All responses will be XML
		res.setContentType("xml");

		//Do our own authentication based on the RSNASESSION cookie.
		//Note that the RSNASESSION cookie is a pointer into a session table
		//maintained by the Authenticator. Each session contains the username
		//as well as the IP address of the client. We need to know who the user
		//is, but the Authenticator will have failed to authenticate this connection
		//because it came from the Query Service, not the client who initiated the
		//session. So our strategy here will be to get the cookie that was passed
		//by the Query Service and ask the Authenticator for the username of the
		//session, then to get the user from the Users class and force that back
		//into the HttpRequest, thus accepting the fact that the cookie is from
		//a different source.
		String username = Authenticator.getInstance().getUsernameForSession(req.getCookie("RSNASESSION"));
		if (username != null) req.setUser(Users.getInstance().getUser(username));

		//Check that this is a post of a MIRCquery.
		if (req.getContentType().toLowerCase().contains("text/xml")) {

			//Yes, get the storage service configuration.
			//Note: the URL will always be /storage/{ssid}
			Path path = req.getParsedPath();
			String ssid = path.element(1);
			MircConfig mc = MircConfig.getInstance();
			Element lib = mc.getLocalLibrary(ssid);

			//Make sure this query is for a known local library
			if (lib == null) {
				res.write( makeMQRString("Unknown index: "+ssid) );
				res.send();
				return;
			}

			//Get the query
			byte[] bytes = FileUtil.getBytes( req.getInputStream(), req.getContentLength() );
			String mircQueryString = "";
			try { mircQueryString = new String(bytes, "UTF-8"); }
			catch (Exception leaveEmpty) { }

			//Parse the MIRCquery
			Query query = null;
			try {
				Document mircQueryXML = XmlUtil.getDocument(mircQueryString);
				//logger.debug("Query document:\n"+XmlUtil.toPrettyString(mircQueryXML));
				query = new Query(mircQueryXML);
			}
			catch (Exception e) {
				res.write(
					makeMQRString(
						"Error parsing the MIRCquery:"
						+ "<br/>Exception message: " + e.getMessage()
						+ "<br/>MIRCquery string length: " + mircQueryString.length()
						+ "<br/>MIRCquery string:<br/><br/>"
						+ StringUtil.makeReadableTagString(mircQueryString)));
				res.send();
				return;
			}

			//Get the user.
			User user = req.getUser();

			//Get the index
			Index index = Index.getInstance(ssid);

			//Do the query
			boolean isOpen = lib.getAttribute("mode").equals("open");
			IndexEntry[] mies = index.query( query, isOpen, user );

			//Sort the results
			String orderBy = query.orderby;
			if (orderBy.equals("title"))
				index.sortByTitle(mies);
			else if (orderBy.equals("pubdate"))
				index.sortByPubDate(mies);
			else
				index.sortByLMDate(mies);

			//Get a document for the MIRCqueryresult
			Document doc = null;
			try { doc = XmlUtil.getDocument(); }
			catch (Exception ex) {
				String message = "Unable to create an XML document for the MIRCqueryresult";
				logger.error(message, ex);
				res.write( makeMQRString(message) );
				res.send();
				return;
			}

			//Select the requested page
			if (query.firstresult < 0) query.firstresult = 0;
			if (query.maxresults <= 0) query.maxresults = 1;
			Element root = doc.createElement("MIRCqueryresult");
			doc.appendChild(root);
			String tagline = XmlUtil.getTextContent(lib, "Library/tagline");
			setPreamble(root, mies.length, tagline);

			//Important note: The imported node must be passed to fixResult.
			//Do not pass the node from the mies array (mies[i].md) and then
			//import the returned node. This would cause fixResult to modify
			//the object in the JDBM's cache, causing problems in the next query.
			String docbase = mc.getLocalAddress() + "/storage/" + ssid + "/";
			int begin = query.firstresult - 1;
			int end = begin + query.maxresults;
			for (int i=begin; ((i<end) && (i<mies.length)); i++) {
				root.appendChild( fixResult(docbase, (Element)doc.importNode(mies[i].md, true), query) );
			}
			//Return the result.
			res.write( XmlUtil.toString(root) );
			res.send();
			logger.debug("Response returned for "+req.path+" ("+(System.currentTimeMillis() - currentTime)+"ms)");
			return;
		}

		else {
			//Unknown content type
			res.write(
				makeMQRString(
					"Unsupported Content-Type: "+req.getContentType()));
			res.send();
		}
	}

	private void setPreamble(Element root, int matches, String tagline) {
		Document doc = root.getOwnerDocument();
		Element preamble = doc.createElement("preamble");
		root.appendChild(preamble);
		if (!tagline.equals("")) {
			Element p = doc.createElement("p");
			Element b = doc.createElement("b");
			b.setTextContent(tagline);
			p.appendChild(b);
			preamble.appendChild(p);
		}
		Element p = doc.createElement("p");
		p.setTextContent("Total search matches: "+matches);
		preamble.appendChild(p);
	}

	private Element fixResult( String context, Element md, Query query ) {
		//First fix the docref
		String path = md.getAttribute("path").trim();
		String docref = context + path;
		md.removeAttribute("filename");

		String qps = "";
		if (query.unknown) qps += "unknown=yes";
		if (!query.bgcolor.equals("")) {
			if (!qps.equals("")) qps += "&";
			qps += "bgcolor="+query.bgcolor;
		}
		if (!query.display.equals("")) {
			if (!qps.equals("")) qps += "&";
			qps += "display="+query.display;
		}
		if (!query.icons.equals("")) {
			if (!qps.equals("")) qps += "&";
			qps += "icons="+query.icons;
		}
		if (!qps.equals("")) docref += "?" + qps;
		md.setAttribute("docref",docref);

		//Now fix the title and abstract elements and remove the alternative elements.
		Element title = null;
		Element altTitle = null;
		Element abs = null;
		Element altAbs = null;
		Element category = null;
		Node child = md.getFirstChild();
		while (child != null) {
			if (child.getNodeType() == Node.ELEMENT_NODE) {
				if (child.getNodeName().equals("title")) title = (Element)child;
				else if (child.getNodeName().equals("alternative-title")) altTitle = (Element)child;
				else if (child.getNodeName().equals("abstract")) abs = (Element)child;
				else if (child.getNodeName().equals("alternative-abstract")) altAbs = (Element)child;
				else if (child.getNodeName().equals("category")) category = (Element)child;
			}
			child = child.getNextSibling();
		}
		if (query.unknown) {
			if (altTitle != null) copy(altTitle, title, "title");
			else {
				while ((child = title.getFirstChild()) != null) title.removeChild(child);
				String titleString = "Unknown";
				if (category != null) {
					String cat = category.getTextContent().trim();
					if (!cat.equals("")) titleString += " - " + cat;
				}
				title.appendChild(title.getOwnerDocument().createTextNode(titleString));
			}
			if (altAbs != null)  copy(altAbs, abs, "abstract");
			else if (abs != null) md.removeChild(abs);
		}
		if (altTitle != null) md.removeChild(altTitle);
		if (altAbs != null) md.removeChild(altAbs);
		return md;
	}

	private void copy(Element from, Element to, String name) {
		if (to == null) {
			to = from.getOwnerDocument().createElement(name);
			from.getParentNode().insertBefore(to,from);
		}
		Node child;
		while ((child = to.getFirstChild()) != null) to.removeChild(child);
		while ((child = from.getFirstChild()) != null) {
			from.removeChild(child);
			to.appendChild(child);
		}
	}

	//Make a MIRCqueryresult string with only a preamble.
	private String makeMQRString(String preambleString) {
		return
			"<MIRCqueryresult>" +
				"<preamble>" + preambleString + "</preamble>" +
			"</MIRCqueryresult>";
	}

    /**
      * Determine whether a user is authorized for a specified
      * action on a MIRCdocument. Possible actions are read, update, export,
      * and delete. An administrator and a document owner are authorized
      * to take any action. Other users are subject to the constraints
      * imposed in the authorization element of the MIRCdocument.
      * @param action the requested action.
      * @param docXML the MIRCdocument DOM object.
      * @param req the servlet request identifying the remote user.
      */
	public static boolean userIsAuthorizedTo(String action, Document docXML, HttpRequest req) {
		try {
			//The admin user is allowed to do anything
			if (req.userHasRole("admin")) return true;

			//The owner is authorized to do anything.
			if (userIsOwner(docXML, req)) return true;

			//For the delete action, only the owner or admin is ever authorized.
			//Therefore, if the action is delete, return false now.
			if (action.equals("delete")) return false;

			//The read access has a special case: if the document has a publish
			//request and the user is a publisher, the user can read the document.
			if (docXML.getDocumentElement().getAttribute("pubreq").equals("yes")
					&& req.userHasRole("publisher")) return true;

			//For non-owners or non-authenticated users, the rule is that if an action
			//authorization does not exist in the document, read and export actions are
			//authorized, but update actions are not.

			//See if the action authorization exists in the document.
			Element auth = XmlUtil.getFirstNamedChild(docXML, "authorization");
			Element actionRoles = XmlUtil.getFirstNamedChild(auth, action);
			if (actionRoles == null) return !action.equals("update");

			//OK, the action authorization exists. Now the rule is that the user must
			//be authenticated and must have a role that is explicitly authorized.

			//Get the list of roles
			String roleList = actionRoles.getTextContent().trim();

			//See if the list is blank, which authorizes nobody.
			if (roleList.equals("")) return false;

			//See if the list includes an asterisk, which authorizes everybody.
			if (roleList.contains("*")) return true;

			//Anything else requires an authenticated user
			if (!req.isFromAuthenticatedUser()) return false;

			//It is not a blanket authorization; check the roles individually.
			//The list can be separated by commas or whitespace.
			//If a specific user is included, it must be placed in [...].
			String user = req.getUser().getUsername();
			String[] roles = roleList.replaceAll("[,\\s]+",",").split(",");
			for (String role : roles) {
				role = role.trim();
				if (role.startsWith("[") && role.endsWith("]")) {
					//It's a username, see if it is the current user.
					role = role.substring(1,role.length()-1).trim();
					if (role.equals(user)) return true;
				}
				else if (req.userHasRole(role)) return true;
			}
		}
		catch (Exception e) { }
		return false;
	}

    /**
      * Determine whether a remote user is the owner of a MIRCdocument.
      * @param docXML the MIRCdocument DOM object.
      * @param req the servlet request identifying the remote user.
      */
	public static boolean userIsOwner(Node docXML, HttpRequest req) {
		try {
			if (req.isFromAuthenticatedUser()) {
				String username = req.getUser().getUsername();
				Element auth = XmlUtil.getFirstNamedChild(docXML, "authorization");
				Element ownerElement = XmlUtil.getFirstNamedChild(auth, "owner");
				if (ownerElement == null) return false;
				String owner = ownerElement.getTextContent().trim();

				//The owner field can be a comma-separated list of usernames.
				//Make a list and check it against the username.
				//Note: this method filters out square brackets so that usernames
				//can be either without brackets or with them (as in the role-based
				//elements - read, update, export).
				String[] owners = owner.replaceAll("[\\[\\],\\s]+",",").split(",");
				for (String ownername : owners) {
					if (ownername.equals(username)) return true;
				}
			}
		}
		catch (Exception e) { }
		return false;
	}

	//Get the local references for an XML document
	private String[] getFilenames(Document mircDocument, File file) {
		HashSet<String> names = new HashSet<String>();
		names.add(file.getName());
		Node root = mircDocument.getDocumentElement();
		getAttributeFilenames(names, root);
		return names.toArray(new String[names.size()]);
	}

	private void getAttributeFilenames(Set<String> names, Node node) {
		if (node.getNodeType() == Node.ELEMENT_NODE) {
			NamedNodeMap attrMap = node.getAttributes();
			int attrlen = attrMap.getLength();
			for (int i=0; i<attrlen; i++) {
				String name = attrMap.item(i).getNodeName();
				if (name.equals("href") || name.equals("src")) {
					String value = attrMap.item(i).getNodeValue();
					if (!value.contains("/") && !value.contains(":")) names.add(value);
				}
			}
			NodeList children = node.getChildNodes();
			if (children != null) {
				for (int i=0; i<children.getLength(); i++) {
					getAttributeFilenames(names,children.item(i));
				}
			}
		}
	}

	//Set up the parameters for the MIRCdocument transform to HTML.
	private Object[] getParams(HttpRequest req, Document doc) {

		User user = req.getUser();
		String username = (user != null) ? user.getUsername() : "";
		Element userPrefs = Preferences.getInstance().get(username, false);

		Path path = req.getParsedPath();

		//Set the parameter for today's date
		String today = StringUtil.getDate("").replaceAll("-","");

		//Get the path to the directory containing the document
		String dirPath = path.subpath(0, path.length()-2); //includes /storage

		//Get the path to the document
		String docPath = path.subpath(0); //includes /storage

		//Make an index path without /storage, for use in publishing and deleting
		String docIndexEntry = path.subpath(1);

		//Set the parameter indicating that whether the user is authenticated
		String userisauthenticated = (req.isFromAuthenticatedUser() ? "yes" : "no");

		//Set the parameter indicating whether the user has a MyRsna account
		String userhasmyrsnaacct = "no";
		if (user != null) {
			Element myrsna = Preferences.getInstance().getMyRsnaAccount(username);
			if (myrsna != null) userhasmyrsnaacct = "yes";
		}

		//Set the parameter indicating whether the user is the owner of the document
		String userisowner = (userIsOwner(doc, req) ? "yes" : "no");

		//Set the parameter indicating whether the user is an admin
		String userisadmin = (req.userHasRole("admin") ? "yes" : "no");

		//Set up the links for:
		//	editing
		//	adding images
		//	sorting the images
		//	publishing
		String editurl = "";
		String addurl = "";
		String sorturl = "";
		String publishurl = "";
		if (userIsAuthorizedTo("update", doc, req)) {
			editurl = "/aauth" + docIndexEntry;
			addurl = "/addimg" + docIndexEntry;
			sorturl = "/sort" + docIndexEntry;
			if (req.userHasRole("publisher")) {
				publishurl = "/publish" + docIndexEntry;
			}
		}

		//Set up the link deleting the document
		String deleteurl = "";
		if (userIsAuthorizedTo("delete", doc, req))
			deleteurl = "/storage/delete" + docIndexEntry;

		//Set up the link for exporting the document
		String exporturl = "";
		if (userIsAuthorizedTo("export", doc, req))
			exporturl = docPath + "?zip";

		//Set up the link for saving the images from this document to the user's file cabinet
		String filecabineturl = "";
		if (req.isFromAuthenticatedUser() && !exporturl.equals(""))
			filecabineturl = "/files/save" + docPath;

		//Set the parameter that identifies this as a preview, in which case
		//certain functions are disabled in the MIRCdocument display.
		String preview = req.getParameter("preview", "no").trim();

		//Set the parameter determining the background color of the rendered document
		String bgcolor = req.getParameter("bgcolor", doc.getDocumentElement().getAttribute("background")).trim();

		//Set the parameter that specifies the display format
		String display = req.getParameter("display", doc.getDocumentElement().getAttribute("display")).trim();

		//Set the parameter that determines whether the icon images
		//are displayed in the mstf and tab display formats
		String icons = req.getParameter("icons", "").trim();

		//Set the parameter for rendering as an unknown case
		String unknown = req.getParameter("unknown");
		if (unknown == null) unknown = "no";

		//Okay, put the parameters in the array.
		Object[] params = new Object[] {
			"today",				today,

			"prefs",				userPrefs.getOwnerDocument(),

			"user-is-authenticated",userisauthenticated,
			"user-has-myrsna-acct",	userhasmyrsnaacct,
			"user-is-owner",		userisowner,
			"user-is-admin",		userisadmin,

			"edit-url",				editurl,
			"add-url",				addurl,
			"sort-url",				sorturl,
			"publish-url",			publishurl,
			"delete-url",			deleteurl,
			"export-url",			exporturl,
			"filecabinet-url",		filecabineturl,

			"preview",				preview,
			"bgcolor",				bgcolor,
			"display",				display,
			"icons",				icons,
			"unknown",				unknown,

			"dir-path",				dirPath,
			"doc-path",				docPath,
			"doc-index-entry",		docIndexEntry
		};
		return params;
	}

	private boolean exportToMyRsna(User user, File zipFile) {
		/*
		try {
			if (user == null) return false;
			MyRsnaSession mrs = MyRsnaSessions.getInstance().getMyRsnaSession(mircUsername);
			if (mrs == null) return false;
			boolean result = mrs.postFile(zipFile, null);
			return result;
		}
		catch (Exception ex) { return false; }
		*/
		return true;
	}

	private String exportToDestination(User user, File zipFile, String url) {

		if (user != null) {
			Preferences prefs = Preferences.getInstance();
			Element userPrefs = prefs.get(user.getUsername(), false);
			NodeList nl = userPrefs.getElementsByTagName("site");
			for (int i=0; i<nl.getLength(); i++) {
				Element site = (Element)nl.item(i);
				if (site.getAttribute("url").equals(url)) {
					OutputStream os = null;
					InputStream is = null;
					String resultText = "";
					try {
						if (url.startsWith("/")) {
							MircConfig mc = MircConfig.getInstance();
							url = mc.getLocalAddress() + url;
						}

						HttpURLConnection conn = HttpUtil.getConnection(url);

						String un = site.getAttribute("username").trim();
						String pw = site.getAttribute("password").trim();
						if (!un.equals("") && !pw.equals("")) {
							User destUser = new User(un, pw);
							conn.setRequestProperty("Authorization", destUser.getBasicAuthorization());
						}
						conn.setRequestProperty("Content-Type", "application/x-zip-compressed");
						conn.setRequestProperty("Content-Length", Long.toString(zipFile.length()));
						conn.connect();

						os = conn.getOutputStream();
						FileUtil.streamFile(zipFile, os);

						int responseCode = conn.getResponseCode();
						resultText += "Response Code = " + responseCode + "\n";
						is = conn.getInputStream();
						resultText += FileUtil.getText(is);
					}
					catch (Exception ex) { }
					FileUtil.close(os);
					return resultText.replace("<br/>", "\n");
				}
			}
		}
		return "Unable to export the zip file";
	}

}
