/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.dlna;

import java.io.*;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.configuration.FormatConfiguration;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.virtual.TranscodeVirtualFolder;
import net.pms.dlna.virtual.VirtualFolder;
import net.pms.dlna.virtual.VirtualVideoAction;
import net.pms.encoders.*;
import net.pms.external.AdditionalResourceFolderListener;
import net.pms.external.ExternalFactory;
import net.pms.external.ExternalListener;
import net.pms.external.StartStopListener;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;
import net.pms.io.OutputParams;
import net.pms.io.ProcessWrapper;
import net.pms.io.SizeLimitInputStream;
import net.pms.network.HTTPResource;
import net.pms.util.*;
import static net.pms.util.StringUtil.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents any item that can be browsed via the UPNP ContentDirectory service.
 *
 * TODO: Change all instance variables to private. For backwards compatibility
 * with external plugin code the variables have all been marked as deprecated
 * instead of changed to private, but this will surely change in the future.
 * When everything has been changed to private, the deprecated note can be
 * removed.
 */
public abstract class DLNAResource extends HTTPResource implements Cloneable, Runnable {
	private final Map<String, Integer> requestIdToRefcount = new HashMap<>();
	private boolean resolved;
	private static final int STOP_PLAYING_DELAY = 4000;
	private static final Logger LOGGER = LoggerFactory.getLogger(DLNAResource.class);
	private final SimpleDateFormat SDF_DATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
	protected PmsConfiguration configuration = PMS.getConfiguration();
//	private boolean subsAreValidForStreaming = false;

	protected static final int MAX_ARCHIVE_ENTRY_SIZE = 10000000;
	protected static final int MAX_ARCHIVE_SIZE_SEEK = 800000000;

	/**
	 * The name displayed on the renderer. Cached the first time getDisplayName(RendererConfiguration) is called.
	 */
	private String displayName;

	/**
	 * The suffix added to the name. Contains additional info about audio and subtitles.
	 */
	private String nameSuffix = "";

	/**
	 * @deprecated This field will be removed. Use {@link net.pms.configuration.PmsConfiguration#getTranscodeFolderName()} instead.
	 */
	@Deprecated
	protected static final String TRANSCODE_FOLDER = Messages.getString("TranscodeVirtualFolder.0"); // localized #--TRANSCODE--#

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected int specificType;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected String id;
	protected String pathId;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected DLNAResource parent;

	/**
	 * @deprecated This field will be removed. Use {@link #getFormat()} and
	 * {@link #setFormat(Format)} instead.
	 */
	@Deprecated
	protected Format ext;

	/**
	 * The format of this resource.
	 */
	private Format format;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected DLNAMediaInfo media;

	/**
	 * @deprecated Use {@link #getMediaAudio()} and {@link
	 * #setMediaAudio(DLNAMediaAudio)} to access this field.
	 */
	@Deprecated
	protected DLNAMediaAudio media_audio;

	/**
	 * @deprecated Use {@link #getMediaSubtitle()} and {@link
	 * #setMediaSubtitle(DLNAMediaSubtitle)} to access this field.
	 */
	@Deprecated
	protected DLNAMediaSubtitle media_subtitle;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected long lastmodified; // TODO make private and rename lastmodified -> lastModified

	/**
	 * Represents the transformation to be used to the file. If null, then
	 *
	 * @see Player
	 */
	private Player player;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected boolean discovered = false;

	private ProcessWrapper externalProcess;

	/**
	 * @deprecated Use #hasExternalSubtitles()
	 */
	@Deprecated
	protected boolean srtFile;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected boolean hasExternalSubtitles;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected int updateId = 1;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	public static int systemUpdateId = 1;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected boolean noName;

	private int nametruncate;
	private DLNAResource first;
	private DLNAResource second;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 *
	 * The time range for the file containing the start and end time in seconds.
	 */
	@Deprecated
	protected Range.Time splitRange = new Range.Time();

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected int splitTrack;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected String fakeParentId;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	// Ditlew - needs this in one of the derived classes
	@Deprecated
	protected RendererConfiguration defaultRenderer;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected boolean avisynth;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 */
	@Deprecated
	protected boolean skipTranscode = false;

	private boolean allChildrenAreFolders = true;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 *
	 * List of children objects associated with this DLNAResource. This is only valid when the DLNAResource is of the container type.
	 */
	@Deprecated
	protected DLNAList children;
	//protected List<DLNAResource> children;

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 *
	 * The numerical ID (1-based index) assigned to the last child of this folder. The next child is assigned this ID + 1.
	 */
	// FIXME should be lastChildId
	@Deprecated
	protected int lastChildrenId = 0; // XXX make private and rename lastChildrenId -> lastChildId

	/**
	 * @deprecated Use standard getter and setter to access this field.
	 *
	 * The last time refresh was called.
	 */
	@Deprecated
	protected long lastRefreshTime;

	@SuppressWarnings("unused")
	private String lastSearch;

	private VirtualFolder dynamicPls;

	protected HashMap<String, Object> attachments = null;

	/**
	 * Returns parent object, usually a folder type of resource. In the DLDI
	 * queries, the UPNP server needs to give out the parent container where
	 * the item is. The <i>parent</i> represents such a container.
	 *
	 * @return Parent object.
	 */
	public DLNAResource getParent() {
		return parent;
	}

	/**
	 * Set the parent object, usually a folder type of resource. In the DLDI
	 * queries, the UPNP server needs to give out the parent container where
	 * the item is. The <i>parent</i> represents such a container.
	 *
	 * @param parent Sets the parent object.
	 */
	public void setParent(DLNAResource parent) {
		this.parent = parent;
	}

	/**
	 * Returns the id of this resource based on the index in its parent
	 * container. Its main purpose is to be unique in the parent container.
	 *
	 * @return The id string.
	 * @since 1.50
	 */
	public String getId() {
		return id;
	}

	/**
	 * Set the ID of this resource based on the index in its parent container.
	 * Its main purpose is to be unique in the parent container. The method is
	 * automatically called by addChildInternal, so most of the time it is not
	 * necessary to call it explicitly.
	 *
	 * @param id
	 * @since 1.50
	 * @see #addChildInternal(DLNAResource)
	 */
	protected void setId(String id) {
		this.id = id;
	}

	public String getPathId() {
		DLNAResource tmp = getParent();
		ArrayList<String> res = new ArrayList<>();
		res.add(getId());
		while (tmp != null) {
			res.add(0, tmp.getId());
			tmp = tmp.getParent();
		}
		pathId = StringUtils.join(res, '.');
		return pathId;
	}

	/**
	 * String representing this resource ID. This string is used by the UPNP
	 * ContentDirectory service. There is no hard spec on the actual numbering
	 * except for the root container that always has to be "0". In PMS the
	 * format used is <i>number($number)+</i>. A common client that expects a
	 * different format than the one used here is the XBox360. PMS translates
	 * the XBox360 queries on the fly. For more info, check
	 * http://www.mperfect.net/whsUpnp360/ .
	 *
	 * @return The resource id.
	 * @since 1.50
	 */
	public String getResourceId() {
		/*if (getId() == null) {
			return null;
		}

		if (parent != null) {
			return parent.getResourceId() + '$' + getId();
		} else {
			return getId();
		}*/
		if (isFolder() && configuration.getAutoDiscover()) {
			return getPathId();
		}
		return getId();
	}

	/**
	 * @see #setId(String)
	 * @param id
	 */
	protected void setIndexId(int id) {
		setId(Integer.toString(id));
	}

	/**
	 *
	 * @return the unique id which identifies the DLNAResource relative to its parent.
	 */
	public String getInternalId() {
		return getId();
	}

	/**
	 *
	 * @return true, if this contain can have a transcode folder
	 */
	public boolean isTranscodeFolderAvailable() {
		return true;
	}

	/**
	 * Any {@link DLNAResource} needs to represent the container or item with a String.
	 *
	 * @return String to be showed in the UPNP client.
	 */
	public abstract String getName();

	public abstract String getSystemName();

	public abstract long length();

	// Ditlew
	public long length(RendererConfiguration mediaRenderer) {
		return length();
	}

	public abstract InputStream getInputStream() throws IOException;

	public abstract boolean isFolder();

	public String getDlnaContentFeatures(RendererConfiguration mediaRenderer) {
		// TODO: Determine renderer's correct localization value
		int localizationValue = 1;
		String dlnaOrgPnFlags = getDlnaOrgPnFlags(mediaRenderer, localizationValue);
		return (dlnaOrgPnFlags != null ? (dlnaOrgPnFlags + ";") : "") + getDlnaOrgOpFlags(mediaRenderer) + ";DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
	}

	public DLNAResource getPrimaryResource() {
		return first;
	}

	public DLNAResource getSecondaryResource() {
		return second;
	}

	public String getFakeParentId() {
		return fakeParentId;
	}

	public void setFakeParentId(String fakeParentId) {
		this.fakeParentId = fakeParentId;
	}

	/**
	 * @return the fake parent id if specified, or the real parent id
	 */
	public String getParentId() {
		if (getFakeParentId() != null) {
			return getFakeParentId();
		} else {
			if (parent != null) {
				return parent.getResourceId();
			} else {
				return "-1";
			}
		}
	}

	public DLNAResource() {
		this.specificType = Format.UNKNOWN;
		//this.children = new ArrayList<DLNAResource>();
		this.children = new DLNAList();
		this.updateId = 1;
		lastSearch = null;
		resHash = 0;
		masterParent = null;
	}

	public DLNAResource(int specificType) {
		this();
		this.specificType = specificType;
	}

	/**
	 * Recursive function that searches through all of the children until it finds
	 * a {@link DLNAResource} that matches the name.<p>
	 * Only used by
	 * {@link net.pms.dlna.RootFolder#addWebFolder(File webConf)
	 * addWebFolder(File webConf)} while parsing the web.conf file.
	 *
	 * @param name String to be compared the name to.
	 * @return Returns a {@link DLNAResource} whose name matches the parameter name
	 * @see #getName()
	 */
	public DLNAResource searchByName(String name) {
		for (DLNAResource child : children) {
			if (child.getName().equals(name)) {
				return child;
			}
		}

		return null;
	}

	/**
	 * @param renderer Renderer for which to check if file is supported.
	 * @return true if the given {@link net.pms.configuration.RendererConfiguration
	 *		RendererConfiguration} can understand type of media. Also returns true
	 * if this DLNAResource is a container.
	 */
	public boolean isCompatible(RendererConfiguration renderer) {
		return format == null
			|| format.isUnknown()
			|| (format.isVideo() && renderer.isVideoSupported())
			|| (format.isAudio() && renderer.isAudioSupported())
			|| (format.isImage() && renderer.isImageSupported());
	}

	/**
	 * Adds a new DLNAResource to the child list. Only useful if this object is
	 * of the container type.
	 * <P>
	 * TODO: (botijo) check what happens with the child object. This function
	 * can and will transform the child object. If the transcode option is set,
	 * the child item is converted to a container with the real item and the
	 * transcode option folder. There is also a parser in order to get the right
	 * name and type, I suppose. Is this the right place to be doing things like
	 * these?
	 * <p>
	 * FIXME: Ideally the logic below is completely renderer-agnostic. Focus on
	 * harvesting generic data and transform it for a specific renderer as late
	 * as possible.
	 *
	 * @param child
	 * DLNAResource to add to a container type.
	 */
	public void addChild(DLNAResource child) {
		addChild(child, true);
	}

	public void addChild(DLNAResource child, boolean isNew) {
		// child may be null (spotted - via rootFolder.addChild() - in a misbehaving plugin
		if (child == null) {
			LOGGER.error("A plugin has attempted to add a null child to \"{}\"", getName());
			LOGGER.debug("Error info:", new NullPointerException("Invalid DLNA resource"));
			return;
		}

		child.parent = this;
		child.masterParent = masterParent;

		if (parent != null) {
			defaultRenderer = parent.getDefaultRenderer();
		}

		if (PMS.filter(defaultRenderer, child)) {
			LOGGER.debug("Resource " + child.getName() + " is filtered out for render " + defaultRenderer.getRendererName());
			return;
		}

		if (configuration.useCode() && !PMS.get().masterCodeValid()) {
			String code = PMS.get().codeDb().getCode(child);
			if (StringUtils.isNotEmpty(code)) {
				DLNAResource cobj = child.isCoded();
				if (cobj == null || !((CodeEnter) cobj).getCode().equals(code)) {
					LOGGER.debug("Resource " + child + " is coded add code folder");
					CodeEnter ce = new CodeEnter(child);
					ce.parent = this;
					ce.defaultRenderer = this.getDefaultRenderer();
					ce.setCode(code);
					addChildInternal(ce);
					return;
				}
			}
		}

		try {
			if (child.isValid()) {
				// Do not add unsupported media format to the list
				if (child.format != null && defaultRenderer != null && !defaultRenderer.supportsFormat(child.format)){
					LOGGER.trace("Ignoring file \"{}\" because it is not supported by renderer \"{}\"", child.getName(), defaultRenderer.getRendererName());
					children.remove(child);
					return;
				}

				LOGGER.trace("{} child \"{}\" with class \"{}\"", isNew ? "Adding new" : "Updating", child.getName(), child.getClass().getName());

				if (allChildrenAreFolders && !child.isFolder()) {
					allChildrenAreFolders = false;
				}

				child.resHash = Math.abs(child.getSystemName().hashCode() + resumeHash());

				DLNAResource resumeRes = null;

				boolean addResumeFile = false;
				ResumeObj r = ResumeObj.create(child);
				if (r != null) {
					resumeRes = child.clone();
					resumeRes.resume = r;
					resumeRes.resHash = child.resHash;
					addResumeFile = true;
				}

				if (child.format != null) {
					String configurationSkipExtensions = configuration.getDisableTranscodeForExtensions();
					String rendererSkipExtensions = null;

					if (defaultRenderer != null) {
						rendererSkipExtensions = defaultRenderer.getStreamedExtensions();
					}

					// Should transcoding be skipped for this format?
					boolean skip = child.format.skip(configurationSkipExtensions, rendererSkipExtensions);
					skipTranscode = skip;

					if (skip) {
						LOGGER.trace("File \"{}\" will be forced to skip transcoding by configuration", child.getName());
					}

					// Determine transcoding possibilities if either
					//    - the format is known to be transcodable
					//    - we have media info (via parserV2, playback info, or a plugin)
					if (child.format.transcodable() || child.media != null) {
						if (child.media == null) {
							child.media = new DLNAMediaInfo();
						}

						// Try to determine a player to use for transcoding.
						Player player = null;

						// First, try to match a player from recently played folder or based on the name of the DLNAResource
						// or its parent. If the name ends in "[unique player id]", that player
						// is preferred.
						String name = getName();

						if (!configuration.isHideRecentlyPlayedFolder()) {
							player = child.player;
						} else {
							for (Player p : PlayerFactory.getPlayers()) {
								String end = "[" + p.id() + "]";

								if (name.endsWith(end)) {
									nametruncate = name.lastIndexOf(end);
									player = p;
									LOGGER.trace("Selecting player based on name end");
									break;
								} else if (parent != null && parent.getName().endsWith(end)) {
									parent.nametruncate = parent.getName().lastIndexOf(end);
									player = p;
									LOGGER.trace("Selecting player based on parent name end");
									break;
								}
							}
						}

						// If no preferred player could be determined from the name, try to
						// match a player based on media information and format.
						if (player == null || (hasExternalSubtitles() && defaultRenderer.isSubtitlesStreamingSupported())) {
							player = child.resolvePlayer(defaultRenderer);
						}
						child.setPlayer(player);
						child.setPreferredMimeType(defaultRenderer);

						if (resumeRes != null) {
							resumeRes.player = player;
						}

						if (!allChildrenAreFolders) {
							child.setDefaultRenderer(defaultRenderer);

							// Should the child be added to the #--TRANSCODE--# folder?
							if ((child.format.isVideo() || child.format.isAudio()) && child.isTranscodeFolderAvailable()) {
								// true: create (and append) the #--TRANSCODE--# folder to this
								// folder if supported/enabled and if it doesn't already exist
								VirtualFolder transcodeFolder = getTranscodeFolder(true);
								if (transcodeFolder != null) {
									VirtualFolder fileTranscodeFolder = new FileTranscodeVirtualFolder(child.getDisplayName(), null);

									DLNAResource newChild = child.clone();
									newChild.player = player;
									newChild.media = child.media;
									fileTranscodeFolder.addChildInternal(newChild);
									LOGGER.trace("Adding \"{}\" to transcode folder for player: \"{}\"", child.getName(), player);

									transcodeFolder.updateChild(fileTranscodeFolder);
								}
							}

							if (child.format.isVideo() && child.isSubSelectable() && !(this instanceof SubSelFile)) {
								VirtualFolder vf = getSubSelector(true);
								if (vf != null) {
									DLNAResource newChild = child.clone();
									newChild.player = player;
									newChild.media = child.media;
									LOGGER.trace("Duplicate subtitle " + child.getName() + " with player: " + player);

									vf.addChild(new SubSelFile(newChild));
								}
							}

							if (configuration.isDynamicPls() &&
								!child.isFolder() &&
								defaultRenderer != null &&
								!defaultRenderer.isNoDynPlsFolder()) {
								addDynamicPls(child);
							}

							for (ExternalListener listener : ExternalFactory.getExternalListeners()) {
								if (listener instanceof AdditionalResourceFolderListener) {
									try {
										((AdditionalResourceFolderListener) listener).addAdditionalFolder(this, child);
									} catch (Throwable t) {
										LOGGER.error("Failed to add additional folder for listener of type: \"{}\"", listener.getClass(), t);
									}
								}
							}
						} else if (!child.format.isCompatible(child.media, defaultRenderer) && !child.isFolder()) {
							LOGGER.trace("Ignoring file \"{}\" because it is not compatible with renderer \"{}\"", child.getName(), defaultRenderer.getRendererName());
							children.remove(child);
						}
					}

					if (resumeRes != null && resumeRes.media != null) {
						resumeRes.media.setThumbready(false);
					}

					if (
						child.format.getSecondaryFormat() != null &&
						child.media != null &&
						defaultRenderer != null &&
						defaultRenderer.supportsFormat(child.format.getSecondaryFormat())
					) {
						DLNAResource newChild = child.clone();
						newChild.setFormat(newChild.format.getSecondaryFormat());
						LOGGER.trace("Detected secondary format \"{}\" for \"{}\"", newChild.format.toString(), newChild.getName());
						newChild.first = child;
						child.second = newChild;

						if (!newChild.format.isCompatible(newChild.media, defaultRenderer)) {
							Player player = PlayerFactory.getPlayer(newChild);
							newChild.setPlayer(player);
							LOGGER.trace("Secondary format \"{}\" will use player \"{}\" for \"{}\"", newChild.format.toString(), newChild.getPlayer().name(), newChild.getName());
						}

						if (child.media != null && child.media.isSecondaryFormatValid()) {
							addChild(newChild);
							LOGGER.trace("Adding secondary format \"{}\" for \"{}\"", newChild.format.toString(), newChild.getName());
						} else {
							LOGGER.trace("Ignoring secondary format \"{}\" for \"{}\": invalid format", newChild.format.toString(), newChild.getName());
						}
					}
				}

				if (addResumeFile) {
					resumeRes.setDefaultRenderer(child.getDefaultRenderer());
					addChildInternal(resumeRes);
				}

				if (isNew) {
					addChildInternal(child);
				}
			}
		} catch (Throwable t) {
			LOGGER.error("Error adding child: \"{}\"", child.getName(), t);

			child.parent = null;
			children.remove(child);
		}
	}

	/**
	 * Determine whether we are a candidate for streaming or transcoding to the
	 * given renderer, and return the relevant player or null as appropriate.
	 *
	 * @param renderer The target renderer
	 * @return A player if transcoding or null if streaming
	 */
	public Player resolvePlayer(RendererConfiguration renderer) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(renderer);
		boolean parserV2 = media != null && renderer != null && renderer.isMediaParserV2();
		Player player = null;

		if (media == null) {
			media = new DLNAMediaInfo();
		}

		if (format == null) {
			// Shouldn't happen, this is just a desperate measure
			Format f = FormatFactory.getAssociatedFormat(getSystemName());
			setFormat(f != null ? f : FormatFactory.getAssociatedFormat(".mpg"));
		}

		// Check if we're a transcode folder item
		if (isNoName() && (getParent() instanceof FileTranscodeVirtualFolder)) {
			// Yes, leave everything as-is
			player = getPlayer();
			LOGGER.trace("Selecting player {} based on transcode item settings", player);
			return player;
		}

		// Try to match a player based on media information and format.
		player = PlayerFactory.getPlayer(this);

		if (player != null) {
			String configurationForceExtensions = configuration.getForceTranscodeForExtensions();
			String rendererForceExtensions = null;

			if (renderer != null) {
				rendererForceExtensions = renderer.getTranscodedExtensions();
			}

			// Should transcoding be forced for this format?
			boolean forceTranscode = format.skip(configurationForceExtensions, rendererForceExtensions);

			if (forceTranscode) {
				LOGGER.trace("File \"{}\" will be forced to be transcoded by configuration", getName());
			}

			boolean hasSubsToTranscode = false;

			boolean hasEmbeddedSubs = false;
			for (DLNAMediaSubtitle s : media.getSubtitleTracksList()) {
				hasEmbeddedSubs = (hasEmbeddedSubs || s.isEmbedded());
			}

			/**
			 * At this stage, we know the media is compatible with the renderer based on its
			 * "Supported" lines, and can therefore be streamed to the renderer without a
			 * player. However, other details about the media can change this, such as
			 * whether it has subtitles that match this user's language settings, so here we
			 * perform those checks.
			 */
			if (format.isVideo() && !configuration.isDisableSubtitles()) {
				if (hasEmbeddedSubs || hasExternalSubtitles()) {
					OutputParams params = new OutputParams(configuration);
					Player.setAudioAndSubs(getSystemName(), media, params); // set proper subtitles in accordance with user setting
					if (params.sid != null) {
						if (params.sid.isExternal()) {
							if (renderer.isExternalSubtitlesFormatSupported(params.sid)) {
								media_subtitle = params.sid;
								media_subtitle.setSubsStreamable(true);
								LOGGER.trace("This video has external subtitles that should be streamed");
							} else {
								forceTranscode = true;
								hasSubsToTranscode = true;
								LOGGER.trace("This video has external subtitles that should be transcoded");
							}
						} else if (params.sid.isEmbedded()) {
							if (renderer.isEmbeddedSubtitlesFormatSupported(params.sid)) {
								LOGGER.trace("This video has embedded subtitles that should be streamed");
							} else {
								forceTranscode = true;
								hasSubsToTranscode = true;
								LOGGER.trace("This video has embedded subtitles that should be transcoded");
							}
						}
					}
				} else {
					LOGGER.trace("This video does not have subtitles");
				}
			}

			boolean isIncompatible = false;
			String audioTracksList = getName() + media.getAudioTracksList().toString();

			String prependTraceReason = "File \"{}\" will not be streamed because ";
			if (!format.isCompatible(media, renderer)) {
				isIncompatible = true;
				LOGGER.trace(prependTraceReason + "it is not supported by the renderer", getName());
			} else if (
				configuration.isEncodedAudioPassthrough() &&
				(
					audioTracksList.contains("audio codec: AC3") ||
					audioTracksList.contains("audio codec: DTS")
				)
			) {
				isIncompatible = true;
				LOGGER.trace(prependTraceReason + "the audio will use the encoded audio passthrough feature", getName());
			} else if (format.isVideo() && parserV2) {
				if (
					renderer.isKeepAspectRatio() &&
					!"16:9".equals(media.getAspectRatioContainer())
				) {
					isIncompatible = true;
					LOGGER.trace(prependTraceReason + "the renderer needs us to add borders to change the aspect ratio from {} to 16/9.", getName(), media.getAspectRatioContainer());
				} else if (!renderer.isResolutionCompatibleWithRenderer(media.getWidth(), media.getHeight())) {
					isIncompatible = true;
					LOGGER.trace(prependTraceReason + "the resolution is incompatible with the renderer.", getName());
				} else if (media.getBitrate() > (renderer.getMaxBandwidth() / 2)) {
					isIncompatible = true;
					LOGGER.trace(prependTraceReason + "the bitrate is too high.", getName());
				}
			}

			// Prefer transcoding over streaming if:
			// 1) the media is unsupported by the renderer, or
			// 2) there are subs to transcode
			boolean preferTranscode = isIncompatible || hasSubsToTranscode;

			// Transcode if:
			// 1) transcoding is forced by configuration, or
			// 2) transcoding is preferred and not prevented by configuration
			if (forceTranscode || (preferTranscode && !isSkipTranscode())) {
				if (parserV2) {
					LOGGER.trace("Final verdict: \"{}\" will be transcoded with player \"{}\" with mime type \"{}\"", getName(), player.toString(), renderer != null ? renderer.getMimeType(mimeType(player)) : media.getMimeType());
				} else {
					LOGGER.trace("Final verdict: \"{}\" will be transcoded with player \"{}\"", getName(), player.toString());
				}
			} else {
				player = null;
				LOGGER.trace("Final verdict: \"{}\" will be streamed", getName());
			}
		}
		return player;
	}


	/**
	 * Set the mimetype for this resource according to the given renderer's
	 * supported preferences, if any.
	 *
	 * @param renderer The renderer
	 * @return The previous mimetype for this resource, or null
	 */
	public String setPreferredMimeType(RendererConfiguration renderer) {
		String prev = media != null ? media.getMimeType() : null;
		boolean parserV2 = media != null && renderer != null && renderer.isMediaParserV2();
		if (parserV2) {
			// See which MIME type the renderer prefers in case it supports the media
			String preferred = renderer.getFormatConfiguration().match(media);
			if (preferred != null) {
				/**
				 * Use the renderer's preferred MIME type for this file.
				 */
				if (!FormatConfiguration.MIMETYPE_AUTO.equals(preferred)) {
					media.setMimeType(preferred);
				}
				LOGGER.trace("File \"{}\" will be sent with MIME type \"{}\"", getName(), preferred);
			}
		}
		return prev;
	}

	/**
	 * Return the transcode folder for this resource.
	 * If UMS is configured to hide transcode folders, null is returned.
	 * If no folder exists and the create argument is false, null is returned.
	 * If no folder exists and the create argument is true, a new transcode folder is created.
	 * This method is called on the parent folder each time a child is added to that parent
	 * (via {@link addChild(DLNAResource)}.
	 *
	 * @param create
	 * @return the transcode virtual folder
	 */
	// XXX package-private: used by MapFile; should be protected?
	TranscodeVirtualFolder getTranscodeFolder(boolean create) {
		if (!isTranscodeFolderAvailable()) {
			return null;
		}

		if (configuration.getHideTranscodeEnabled()) {
			return null;
		}

		// search for transcode folder
		for (DLNAResource child : children) {
			if (child instanceof TranscodeVirtualFolder) {
				return (TranscodeVirtualFolder) child;
			}
		}

		if (create) {
			TranscodeVirtualFolder transcodeFolder = new TranscodeVirtualFolder(null, configuration);
			addChildInternal(transcodeFolder);
			return transcodeFolder;
		}

		return null;
	}

	/**
	 * (Re)sets the given DNLA resource as follows:
	 *    - if it's already one of our children, renew it
	 *    - or if we have another child with the same name, replace it
	 *    - otherwise add it as a new child.
	 *
	 * @param child the DLNA resource to update
	 */
	public void updateChild(DLNAResource child) {
		DLNAResource found = children.contains(child) ?
			child : searchByName(child.getName());
		if (found != null) {
			if (child != found) {
				// Replace
				child.parent = this;
				child.setIndexId(GlobalIdRepo.parseIndex(found.getInternalId()));
				children.set(children.indexOf(found), child);
			}
			// Renew
			addChild(child, false);
		} else {
			// Not found, it's new
			addChild(child, true);
		}
	}

	/**
	 * Adds the supplied DLNA resource in the internal list of child nodes,
	 * and sets the parent to the current node. Avoids the side-effects
	 * associated with the {@link #addChild(DLNAResource)} method.
	 *
	 * @param child the DLNA resource to add to this node's list of children
	 */
	protected synchronized void addChildInternal(DLNAResource child) {
		if (child.getInternalId() != null) {
			LOGGER.debug(
				"Node ({}) already has an ID ({}), which is overridden now. The previous parent node was: {}",
				new Object[] {
					child.getClass().getName(),
					child.getResourceId(),
					child.parent
				}
			);
		}

		children.add(child);
		child.parent = this;

		/*setLastChildId(getLastChildId() + 1);
		child.setIndexId(getLastChildId());*/
		PMS.getGlobalRepo().add(child);
		if (defaultRenderer != null) {
			defaultRenderer.cachePut(child);
		}
	}

	public synchronized DLNAResource getDLNAResource(String objectId, RendererConfiguration renderer) {
		// this method returns exactly ONE (1) DLNAResource
		// it's used when someone requests playback of media. The media must
		// first have been discovered by someone first (unless it's a Temp item)

		// Get/create/reconstruct it if it's a Temp item
		if (objectId.contains("$Temp/")) {
			return Temp.get(objectId, renderer);
		}

		// Now strip off the filename
		objectId = StringUtils.substringBefore(objectId, "/");

		/*DLNAResource dlna = renderer.cacheGet(objectId);
		if (dlna == null) {
			// nothing found. Try again
			LOGGER.debug("requested media ({}) not discovered by {}, trying other renderers", objectId, renderer);
			for (RendererConfiguration r : PMS.get().getRenders()) {
				if (r.equals(renderer)) {
					// no need to search ourself again
					continue;
				}
				DLNAResource res = r.cacheGet(objectId);
				if (res != null && !res.isFolder()) {
					// only non-folders can be found this way
					LOGGER.debug("render " + r +" had found media " + res);
					return res;
				}
			}
		}
		return dlna;*/
		DLNAResource dlna;
		String[] ids = objectId.split("\\.");
		if (objectId.equals("0")) {
			dlna = renderer.getRootFolder();
		} else {
			// only allow the last one here
			dlna = PMS.getGlobalRepo().get(ids[ids.length - 1]);//renderer.cacheGet(objectId);
		}

		if (dlna == null) {
			return null;
		}

		if (PMS.filter(renderer, dlna)) {
			// apply filter to make sure we're not bypassing it...
			LOGGER.debug("Resource " + dlna.getName() + " is filtered out for render " + renderer.getRendererName());
			return null;
		}

		return dlna;
	}

	/**
	 * First thing it does it searches for an item matching the given objectID.
	 * If children is false, then it returns the found object as the only object in the list.
	 * TODO: (botijo) This function does a lot more than this!
	 *
	 * @param objectId ID to search for.
	 * @param children State if you want all the children in the returned list.
	 * @param start
	 * @param count
	 * @param renderer Renderer for which to do the actions.
	 * @return List of DLNAResource items.
	 * @throws IOException
	 */
	public synchronized List<DLNAResource> getDLNAResources(String objectId, boolean children, int start, int count, RendererConfiguration renderer) throws IOException {
		return getDLNAResources(objectId, children, start, count, renderer, null);
	}

	public synchronized List<DLNAResource> getDLNAResources(String objectId, boolean returnChildren, int start, int count, RendererConfiguration renderer, String searchStr) {
		ArrayList<DLNAResource> resources = new ArrayList<>();

		// Get/create/reconstruct it if it's a Temp item
		if (objectId.contains("$Temp/")) {
			List<DLNAResource> items = Temp.asList(objectId);
			return items != null ? items : resources;
		}

		// Now strip off the filename
		objectId = StringUtils.substringBefore(objectId, "/");

		DLNAResource dlna;
		String[] ids = objectId.split("\\.");
		if (objectId.equals("0")) {
			dlna = renderer.getRootFolder();
		} else {
			dlna = PMS.getGlobalRepo().get(ids[ids.length - 1]);//renderer.cacheGet(objectId);
		}

		if (dlna == null) {
			// nothing in the cache do a traditional search
			dlna = search(ids, renderer);
			//dlna = search(objectId, count, renderer, searchStr);
		}

		if (dlna != null) {
			if (!(dlna instanceof CodeEnter) && !isCodeValid(dlna)) {
				LOGGER.debug("code is not valid any longer");
				return resources;
			}
			String systemName = dlna.getSystemName();
			dlna.setDefaultRenderer(renderer);

			if (!returnChildren) {
				resources.add(dlna);
				dlna.refreshChildrenIfNeeded(searchStr);
			} else {
				dlna.discoverWithRenderer(renderer, count, true, searchStr);

				if (count == 0) {
					count = dlna.getChildren().size();
				}

				if (count > 0) {
					ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(count);

					int nParallelThreads = 3;
					if (dlna instanceof DVDISOFile) {
						nParallelThreads = 1; // Some DVD drives die with 3 parallel threads
					}

					ThreadPoolExecutor tpe = new ThreadPoolExecutor(
						Math.min(count, nParallelThreads),
						count,
						20,
						TimeUnit.SECONDS,
						queue
					);

					for (int i = start; i < start + count; i++) {
						if (i < dlna.getChildren().size()) {
							final DLNAResource child = dlna.getChildren().get(i);
							if (child != null) {
								tpe.execute(child);
								resources.add(child);
							} else {
								LOGGER.warn("null child at index {} in {}", i, systemName);
							}
						}
					}

					try {
						tpe.shutdown();
						tpe.awaitTermination(20, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						LOGGER.error("error while shutting down thread pool executor for " + systemName, e);
					}

					LOGGER.trace("End of analysis for " + systemName);
				}
			}
		}

		lastSearch = searchStr;
		return resources;
	}

	protected void refreshChildrenIfNeeded(String search) {
		if (isDiscovered() && shouldRefresh(search)) {
			refreshChildren(search);
			notifyRefresh();
		}
	}

	/**
	 * Update the last refresh time.
	 */
	protected void notifyRefresh() {
		lastRefreshTime = System.currentTimeMillis();
		updateId += 1;
		systemUpdateId += 1;
	}

	final protected void discoverWithRenderer(RendererConfiguration renderer, int count, boolean forced, String searchStr) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(renderer);
		// Discover children if it hasn't been done already
		if (!isDiscovered()) {
			if (configuration.getFolderLimit() && depthLimit()) {
				if (renderer.getConfName().equalsIgnoreCase("Playstation 3") || renderer.isXbox360()) {
					LOGGER.info("Depth limit potentionally hit for " + getDisplayName());
				}

				if (defaultRenderer != null) {
					defaultRenderer.addFolderLimit(this);
				}
			}

			discoverChildren(searchStr);
			boolean ready;

			if (renderer.isMediaParserV2() && renderer.isDLNATreeHack()) {
				ready = analyzeChildren(count);
			} else {
				ready = analyzeChildren(-1);
			}

			if (!renderer.isMediaParserV2() || ready) {
				setDiscovered(true);
			}

			notifyRefresh();
		} else {
			// if forced, then call the old 'refreshChildren' method
			LOGGER.trace("discover {} refresh forced: {}", getResourceId(), forced);
			/*if (forced && shouldRefresh(searchStr)) {
				doRefreshChildren(searchStr);
				notifyRefresh();
			} */
			if (forced) {
				// This seems to follow the same code path as the else below in the case of MapFile, because
				// refreshChildren calls shouldRefresh -> isRefreshNeeded -> doRefreshChildren, which is what happens below
				// (refreshChildren is not overridden in MapFile)
				if (refreshChildren(searchStr)) {
					notifyRefresh();
				}
			} else {
				// if not, then the regular isRefreshNeeded/doRefreshChildren pair.
				if (shouldRefresh(searchStr)) {
					doRefreshChildren(searchStr);
					notifyRefresh();
				}
			}
		}
	}

	private boolean shouldRefresh(String searchStr) {
		return isRefreshNeeded();
	}

	@Override
	public void run() {
		if (first == null) {
			resolve();
			if (second != null) {
				second.resolve();
			}
		}
	}

	/**
	 * Recursive function that searches for a given ID.
	 *
	 * @param searchId ID to search for.
	 * @param count
	 * @param renderer
	 * @param searchStr
	 * @return Item found, or null otherwise.
	 * @see #getId()
	 */
	public DLNAResource search(String searchId, int count, RendererConfiguration renderer, String searchStr) {
		if (id != null && searchId != null) {
			String[] indexPath = searchId.split("\\$", 2);
			if (id.equals(indexPath[0])) {
				if (indexPath.length == 1 || indexPath[1].length() == 0) {
					return this;
				} else {
					discoverWithRenderer(renderer, count, false, null);

					for (DLNAResource file : children) {
						DLNAResource found = file.search(indexPath[1], count, renderer, null);
						if (found != null) {
							// Make sure it's ready
							//found.resolve();
							return found;
						}
					}
				}
			} else {
				return null;
			}
		}

		return null;
	}

	private DLNAResource search(String[] ids, RendererConfiguration r) {
		DLNAResource dlna;
		for (String id : ids) {
			if (id.equals("0")) {
				dlna = r.getRootFolder();
			} else {
				dlna = PMS.getGlobalRepo().get(id);
			}

			if (dlna == null) {
				LOGGER.debug("Bad id {} found in path", id);
				return null;
			}

			dlna.discoverWithRenderer(r, 0, false, null);
		}

		return PMS.getGlobalRepo().get(ids[ids.length - 1]);
	}

	public DLNAResource search(String searchId) {
		if (id != null && searchId != null) {
			if (getResourceId().equals(searchId)) {
				return this;
			} else {
				for (DLNAResource file : children) {
					DLNAResource found = file.search(searchId);
					if (found != null) {
						// Make sure it's ready
						//found.resolve();
						return found;
					}
				}
			}
		}

		return null;
	}

	/**
	 * TODO: (botijo) What is the intention of this function? Looks like a prototype to be overloaded.
	 */
	public void discoverChildren() {
	}

	public void discoverChildren(String str) {
		discoverChildren();
	}

	/**
	 * TODO: (botijo) What is the intention of this function? Looks like a prototype to be overloaded.
	 *
	 * @param count
	 * @return Returns true
	 */
	public boolean analyzeChildren(int count) {
		return true;
	}

	/**
	 * Reload the list of children.
	 */
	public void doRefreshChildren() {
	}

	public void doRefreshChildren(String search) {
		doRefreshChildren();
	}

	/**
	 * @return true, if the container is changed, so refresh is needed.
	 * This could be called a lot of times.
	 */
	public boolean isRefreshNeeded() {
		return false;
	}

	/**
	 * This method gets called only for the browsed folder, and not for the
	 * parent folders. (And in the media library scan step too). Override in
	 * plugins when you do not want to implement proper change tracking, and
	 * you do not care if the hierarchy of nodes getting invalid between.
	 *
	 * @return True when a refresh is needed, false otherwise.
	 */
	public boolean refreshChildren() {
		if (isRefreshNeeded()) {
			doRefreshChildren();
			return true;
		}

		return false;
	}

	public boolean refreshChildren(String search) {
		if (shouldRefresh(search)) {
			doRefreshChildren(search);
			return true;
		}

		return false;
	}

	/**
	 * @deprecated Use {@link #resolveFormat()} instead.
	 */
	@Deprecated
	protected void checktype() {
		resolveFormat();
	}

	/**
	 * Sets the resource's {@link net.pms.formats.Format} according to its filename
	 * if it isn't set already.
	 *
	 * @since 1.90.0
	 */
	protected void resolveFormat() {
		if (format == null) {
			format = FormatFactory.getAssociatedFormat(getSystemName());
		}

		if (format != null && format.isUnknown()) {
			format.setType(getSpecificType());
		}
	}

	/**
	 * Hook to lazily initialise immutable resources e.g. ISOs, zip files &amp;c.
	 *
	 * @since 1.90.0
	 * @see #resolve()
	 */
	protected void resolveOnce() { }

	/**
	 * Resolve events are hooks that allow DLNA resources to perform various forms
	 * of initialisation when navigated to or streamed i.e. they function as lazy
	 * constructors.
	 *
	 * This method is called by request handlers for a) requests for a stream
	 * or b) content directory browsing i.e. for potentially every request for a file or
	 * folder the renderer hasn't cached. Many resource types are immutable (e.g. playlists,
	 * zip files, DVD ISOs &amp;c.) and only need to respond to this event once.
	 * Most resource types don't "subscribe" to this event at all. This default implementation
	 * provides hooks for immutable resources and handles the event for resource types that
	 * don't care about it. The rest override this method and handle it accordingly. Currently,
	 * the only resource type that overrides it is {@link RealFile}.
	 *
	 * Note: resolving a resource once (only) doesn't prevent children being added to or
	 * removed from it (if supported). There are other mechanisms for that e.g.
	 * {@link #doRefreshChildren()} (see {@link Feed} for an example).
	 */
	public synchronized void resolve() {
		if (!resolved) {
			resolveOnce();
			// if resolve() isn't overridden, this file/folder is immutable
			// (or doesn't respond to resolve events, which amounts to the
			// same thing), so don't spam it with this event again.
			resolved = true;
		}
	}

	// Ditlew
	/**
	 * Returns the display name for the default renderer.
	 *
	 * @return The display name.
	 * @see #getDisplayName(RendererConfiguration, boolean)
	 */
	public String getDisplayName() {
		return getDisplayName(null, true);
	}

	/**
	 * @param mediaRenderer Media Renderer for which to show information.
	 * @return String representing the item.
	 * @see #getDisplayName(RendererConfiguration, boolean)
	 */
	public String getDisplayName(RendererConfiguration mediaRenderer) {
		return getDisplayName(mediaRenderer, true);
	}

	/**
	 * Returns the DisplayName that is shown to the Renderer.
	 * Extra info might be appended depending on the settings, like item duration.
	 * This is based on {@link #getName()}.
	 *
	 * @param mediaRenderer Media Renderer for which to show information.
	 * @param withSuffix Whether to include additional media info
	 * @return String representing the item.
	 */
	private String getDisplayName(RendererConfiguration mediaRenderer, boolean withSuffix) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(mediaRenderer);
		// displayName shouldn't be cached, since device configurations may differ
//		if (displayName != null) { // cached
//			return withSuffix ? (displayName + nameSuffix) : displayName;
//		}

		displayName = getName();
		nameSuffix = "";
		String subtitleFormat;
		String subtitleLanguage;
		boolean isNamedNoEncoding = false;
		boolean subsAreValidForStreaming = media_subtitle != null && media_subtitle.isStreamable() && player == null;
		if (
			this instanceof RealFile &&
			(
				configuration.isHideExtensions() ||
				configuration.isPrettifyFilenames()
			) &&
			!isFolder()
		) {
			if (configuration.isPrettifyFilenames() && getFormat() != null && getFormat().isVideo()) {
				RealFile rf = (RealFile) this;
				displayName = FileUtil.getFileNameWithRewriting(displayName, rf.getFile());
			} else {
				displayName = FileUtil.getFileNameWithoutExtension(displayName);
			}
		}

		if (player != null) {
			if (isNoName()) {
				displayName = "[" + player.name() + "]";
			} else {
				// Ditlew - WDTV Live don't show durations otherwise, and this is useful for finding the main title
				if (mediaRenderer != null && mediaRenderer.isShowDVDTitleDuration() && media != null && media.getDvdtrack() > 0) {
					nameSuffix += " - " + media.getDurationString();
				}

				if (!configuration.isHideEngineNames()) {
					nameSuffix += " [" + player.name() + "]";
				}
			}
		} else {
			if (isNoName()) {
				displayName = Messages.getString("DLNAResource.0");
				isNamedNoEncoding = true;
				if (subsAreValidForStreaming) {
					isNamedNoEncoding = false;
				}
			} else if (nametruncate > 0) {
				displayName = displayName.substring(0, nametruncate).trim();
			}
		}

		if (
			hasExternalSubtitles() &&
			!isNamedNoEncoding &&
			media_audio == null &&
			media_subtitle == null &&
			!configuration.hideSubsInfo() &&
			(
				player == null ||
				player.isExternalSubtitlesSupported()
			)
		) {
			nameSuffix += " " + Messages.getString("DLNAResource.1");
		}

		if (getMediaAudio() != null) {
			String audioLanguage = "/" + getMediaAudio().getLangFullName();
			if ("/Undetermined".equals(audioLanguage)) {
				audioLanguage = "";
			}

			String audioTrackTitle = "";
			if (
				getMediaAudio().getAudioTrackTitleFromMetadata() != null &&
				!"".equals(getMediaAudio().getAudioTrackTitleFromMetadata()) &&
				mediaRenderer != null &&
				mediaRenderer.isShowAudioMetadata()
			) {
				audioTrackTitle = " (" + getMediaAudio().getAudioTrackTitleFromMetadata() + ")";
			}

			displayName = player != null ? ("[" + player.name() + "]") : "";
			nameSuffix = " {Audio: " + getMediaAudio().getAudioCodec() + audioLanguage + audioTrackTitle + "}";
		}

		if (
			media_subtitle != null &&
			media_subtitle.getId() != -1 &&
			!configuration.hideSubsInfo()
		) {
			subtitleFormat = media_subtitle.getType().getDescription();
			if ("(Advanced) SubStation Alpha".equals(subtitleFormat)) {
				subtitleFormat = "SSA";
			} else if ("Blu-ray subtitles".equals(subtitleFormat)) {
				subtitleFormat = "PGS";
			}

			subtitleLanguage = "/" + media_subtitle.getLangFullName();
			if ("/Undetermined".equals(subtitleLanguage)) {
				subtitleLanguage = "";
			}

			String subtitlesTrackTitle = "";
			if (
				media_subtitle.getSubtitlesTrackTitleFromMetadata() != null &&
				!"".equals(media_subtitle.getSubtitlesTrackTitleFromMetadata()) &&
				mediaRenderer != null &&
				mediaRenderer.isShowSubMetadata()
			) {
				subtitlesTrackTitle = " (" + media_subtitle.getSubtitlesTrackTitleFromMetadata() + ")";
			}

			String subsDescription = Messages.getString("DLNAResource.2") + subtitleFormat + subtitleLanguage + subtitlesTrackTitle;
			if (subsAreValidForStreaming) {
				nameSuffix += " {" + Messages.getString("DLNAResource.3") + subsDescription + "}";
			} else {
				nameSuffix += " {" + subsDescription + "}";
			}
		}

		if (isAvisynth()) {
			displayName = (player != null ? ("[" + player.name()) : "") + " + AviSynth]";
		}

		if (getSplitRange().isEndLimitAvailable()) {
			displayName = ">> " + convertTimeToString(getSplitRange().getStart(), DURATION_TIME_FORMAT);
		}

		return withSuffix ? (displayName + nameSuffix) : displayName;
	}

	/**
	 * Prototype for returning URLs.
	 *
	 * @return An empty URL
	 */
	protected String getFileURL() {
		return getURL("");
	}

	/**
	 * @return Returns a URL pointing to an image representing the item. If
	 * none is available, "thumbnail0000.png" is used.
	 */
	protected String getThumbnailURL() {
		return getURL("thumbnail0000");
	}

	/**
	 * @param prefix
	 * @return Returns a URL for a given media item. Not used for container types.
	 */
	public String getURL(String prefix) {
		return getURL(prefix, false);
	}

	public String getURL(String prefix, boolean useSystemName) {
		StringBuilder sb = new StringBuilder();
		sb.append(PMS.get().getServer().getURL());
		sb.append("/get/");
		sb.append(getResourceId()); //id
		sb.append("/");
		sb.append(prefix);
		sb.append(encode(useSystemName ? getSystemName() : getName()));
		return sb.toString();
	}

	/**
	 * @param subs
	 * @return Returns a URL for a given subtitles item. Not used for container types.
	 */
	protected String getSubsURL(DLNAMediaSubtitle subs) {
		StringBuilder sb = new StringBuilder();
		sb.append(PMS.get().getServer().getURL());
		sb.append("/get/");
		sb.append(getResourceId()); //id
		sb.append("/");
		sb.append("subtitle0000");
		sb.append(encode(subs.getExternalFile().getName()));
		return sb.toString();
	}

	/**
	 * Transforms a String to UTF-8.
	 *
	 * @param s
	 * @return Transformed string s in UTF-8 encoding.
	 */
	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			LOGGER.debug("Caught exception", e);
		}

		return "";
	}

	/**
	 * @return Number of children objects. This might be used in the DLDI
	 * response, as some renderers might not have enough memory to hold the
	 * list for all children.
	 */
	public int childrenNumber() {
		if (children == null) {
			return 0;
		}

		return children.size();
	}

	/**
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#clone()
	 */
	@Override
	protected DLNAResource clone() {
		DLNAResource o = null;
		try {
			o = (DLNAResource) super.clone();
			o.setId(null);

			// Clear the cached display name and suffix
			o.displayName = null;
			o.nameSuffix = "";
			// Make sure clones (typically #--TRANSCODE--# folder files)
			// have the option to respond to resolve events
			o.resolved = false;

			if (media != null) {
				o.media = (DLNAMediaInfo) media.clone();
			}
		} catch (CloneNotSupportedException e) {
			LOGGER.error(null, e);
		}

		return o;
	}

	/**
	 * DLNA.ORG_OP flags
	 *
	 * Two booleans (binary digits) which determine what transport operations the renderer is allowed to
	 * perform (in the form of HTTP request headers): the first digit allows the renderer to send
	 * TimeSeekRange.DLNA.ORG (seek by time) headers; the second allows it to send RANGE (seek by byte)
	 * headers.
	 *
	 *    00 - no seeking (or even pausing) allowed
	 *    01 - seek by byte
	 *    10 - seek by time
	 *    11 - seek by both
	 *
	 * See here for an example of how these options can be mapped to keys on the renderer's controller:
	 * http://www.ps3mediaserver.org/forum/viewtopic.php?f=2&t=2908&p=12550#p12550
	 *
	 * Note that seek-by-byte is the preferred option for streamed files [1] and seek-by-time is the
	 * preferred option for transcoded files.
	 *
	 * [1] see http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&t=15841&p=76201#p76201
	 *
	 * seek-by-time requires a) support by the renderer (via the SeekByTime renderer conf option)
	 * and b) support by the transcode engine.
	 *
	 * The seek-by-byte fallback doesn't work well with transcoded files [2], but it's better than
	 * disabling seeking (and pausing) altogether.
	 *
	 * [2] http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&t=3507&p=16567#p16567 (bottom post)
	 *
	 * @param mediaRenderer
	 * 			Media Renderer for which to represent this information.
	 * @return String representation of the DLNA.ORG_OP flags
	 */
	private String getDlnaOrgOpFlags(RendererConfiguration mediaRenderer) {
		String dlnaOrgOpFlags = "01"; // seek by byte (exclusive)

		if (mediaRenderer.isSeekByTime() && player != null && player.isTimeSeekable()) {
			/**
			 * Some renderers - e.g. the PS3 and Panasonic TVs - behave erratically when
			 * transcoding if we keep the default seek-by-byte permission on when permitting
			 * seek-by-time: http://www.ps3mediaserver.org/forum/viewtopic.php?f=6&t=15841
			 *
			 * It's not clear if this is a bug in the DLNA libraries of these renderers or a bug
			 * in UMS, but setting an option in the renderer conf that disables seek-by-byte when
			 * we permit seek-by-time - e.g.:
			 *
			 *    SeekByTime = exclusive
			 *
			 * works around it.
			 */

			/**
			 * TODO (e.g. in a beta release): set seek-by-time (exclusive) here for *all* renderers:
			 * seek-by-byte isn't needed here (both the renderer and the engine support seek-by-time)
			 * and may be buggy on other renderers than the ones we currently handle.
			 *
			 * In the unlikely event that a renderer *requires* seek-by-both here, it can
			 * opt in with (e.g.):
			 *
			 *    SeekByTime = both
			 */
			if (mediaRenderer.isSeekByTimeExclusive()) {
				dlnaOrgOpFlags = "10"; // seek by time (exclusive)
			} else {
				dlnaOrgOpFlags = "11"; // seek by both
			}
		}

		return "DLNA.ORG_OP=" + dlnaOrgOpFlags;
	}

	/**
	 * Creates the DLNA.ORG_PN to send.
	 * DLNA.ORG_PN is a string that tells the renderer what type of file to expect, like its
	 * container, framerate, codecs and resolution.
	 * Some renderers will not play a file if it has the wrong DLNA.ORG_PN string, while others
	 * are fine with any string or even nothing.
	 *
	 * @param mediaRenderer
	 * 			Media Renderer for which to represent this information.
	 * @param localizationValue
	 * @return String representation of the DLNA.ORG_PN flags
	 */
	private String getDlnaOrgPnFlags(RendererConfiguration mediaRenderer, int localizationValue) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(mediaRenderer);
		String mime = getRendererMimeType(mediaRenderer);

		String dlnaOrgPnFlags = null;

		if (mediaRenderer.isDLNAOrgPNUsed() || mediaRenderer.isAccurateDLNAOrgPN()) {
			if (mediaRenderer.isPS3()) {
				if (mime.equals(DIVX_TYPEMIME)) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=AVI";
				} else if (mime.equals(WMV_TYPEMIME) && media != null && media.getHeight() > 700) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=WMVHIGH_PRO";
				}
			} else {
				if (mime.equals(MPEG_TYPEMIME)) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=" + getMPEG_PS_PALLocalizedValue(localizationValue);

					if (player != null) {
						// VLC Web Video (Legacy) and tsMuxeR always output MPEG-TS
						boolean isFileMPEGTS = TsMuxeRVideo.ID.equals(player.id()) || VideoLanVideoStreaming.ID.equals(player.id());

						// Check if the renderer settings make the current engine always output MPEG-TS
						if (
							!isFileMPEGTS &&
							mediaRenderer.isTranscodeToMPEGTS() &&
							(
								MEncoderVideo.ID.equals(player.id()) ||
								FFMpegVideo.ID.equals(player.id()) ||
								VLCVideo.ID.equals(player.id()) ||
								AviSynthFFmpeg.ID.equals(player.id()) ||
								AviSynthMEncoder.ID.equals(player.id())
							)
						) {
							isFileMPEGTS = true;
						}

						boolean isMuxableResult = getMedia() != null && getMedia().isMuxable(mediaRenderer);

						// If the engine is capable of automatically muxing to MPEG-TS and the setting is enabled, it might be MPEG-TS
						if (
							!isFileMPEGTS &&
							(
								(
									configuration.isMencoderMuxWhenCompatible() &&
									MEncoderVideo.ID.equals(player.id())
								) ||
								(
									configuration.isFFmpegMuxWithTsMuxerWhenCompatible() &&
									FFMpegVideo.ID.equals(player.id())
								)
							)
						) {
							/**
							 * Media renderer needs ORG_PN to be accurate.
							 * If the value does not match the media, it won't play the media.
							 * Often we can lazily predict the correct value to send, but due to
							 * MEncoder needing to mux via tsMuxeR, we need to work it all out
							 * before even sending the file list to these devices.
							 * This is very time-consuming so we should a) avoid using this
							 * chunk of code whenever possible, and b) design a better system.
							 * Ideally we would just mux to MPEG-PS instead of MPEG-TS so we could
							 * know it will always be PS, but most renderers will not accept H.264
							 * inside MPEG-PS. Another option may be to always produce MPEG-TS
							 * instead and we should check if that will be OK for all renderers.
							 *
							 * This code block comes from Player.setAudioAndSubs()
							 */
							if (mediaRenderer.isAccurateDLNAOrgPN()) {
								boolean finishedMatchingPreferences = false;
								OutputParams params = new OutputParams(configuration);
								if (params.aid == null && media != null && media.getFirstAudioTrack() != null) {
									// check for preferred audio
									DLNAMediaAudio dtsTrack = null;
									StringTokenizer st = new StringTokenizer(configuration.getAudioLanguages(), ",");
									while (st.hasMoreTokens()) {
										String lang = st.nextToken().trim();
										LOGGER.trace("Looking for an audio track with lang: " + lang);
										for (DLNAMediaAudio audio : media.getAudioTracksList()) {
											if (audio.matchCode(lang)) {
												params.aid = audio;
												LOGGER.trace("Matched audio track: " + audio);
												break;
											}

											if (dtsTrack == null && audio.isDTS()) {
												dtsTrack = audio;
											}
										}
									}

									// preferred audio not found, take a default audio track, dts first if available
									if (dtsTrack != null) {
										params.aid = dtsTrack;
										LOGGER.trace("Found priority audio track with DTS: " + dtsTrack);
									} else {
										params.aid = media.getAudioTracksList().get(0);
										LOGGER.trace("Chose a default audio track: " + params.aid);
									}
								}

								String currentLang = null;
								DLNAMediaSubtitle matchedSub = null;
								if (params.aid != null) {
									currentLang = params.aid.getLang();
								}

								if (params.sid != null && params.sid.getId() == -1) {
									LOGGER.trace("Don't want subtitles!");
									params.sid = null;
									media_subtitle = params.sid;
									finishedMatchingPreferences = true;
								}

								/**
								 * Check for live subtitles
								 */
								if (!finishedMatchingPreferences && params.sid != null && !StringUtils.isEmpty(params.sid.getLiveSubURL())) {
									LOGGER.debug("Live subtitles " + params.sid.getLiveSubURL());
									try {
										matchedSub = params.sid;
										String file = OpenSubtitle.fetchSubs(matchedSub.getLiveSubURL(), matchedSub.getLiveSubFile());
										if (!StringUtils.isEmpty(file)) {
											matchedSub.setExternalFile(new File(file));
											params.sid = matchedSub;
											media_subtitle = params.sid;
											finishedMatchingPreferences = true;
										}
									} catch (IOException e) {
									}
								}

								if (!finishedMatchingPreferences) {
									StringTokenizer st = new StringTokenizer(configuration.getAudioSubLanguages(), ";");

									/**
									 * Check for external and internal subtitles matching the user's language
									 * preferences
									 */
									boolean matchedInternalSubtitles = false;
									boolean matchedExternalSubtitles = false;
									while (st.hasMoreTokens()) {
										String pair = st.nextToken();
										if (pair.contains(",")) {
											String audio = pair.substring(0, pair.indexOf(','));
											String sub = pair.substring(pair.indexOf(',') + 1);
											audio = audio.trim();
											sub = sub.trim();
											LOGGER.trace("Searching for a match for: " + currentLang + " with " + audio + " and " + sub);

											if (Iso639.isCodesMatching(audio, currentLang) || (currentLang != null && audio.equals("*"))) {
												if (sub.equals("off")) {
													/**
													 * Ignore the "off" language for external subtitles if the user setting is enabled
													 * TODO: Prioritize multiple external subtitles properly instead of just taking the first one we load
													 */
													if (configuration.isForceExternalSubtitles()) {
														for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
															if (present_sub.getExternalFile() != null) {
																matchedSub = present_sub;
																matchedExternalSubtitles = true;
																LOGGER.trace("Ignoring the \"off\" language because there are external subtitles");
																break;
															}
														}
													}

													if (!matchedExternalSubtitles) {
														matchedSub = new DLNAMediaSubtitle();
														matchedSub.setLang("off");
													}
												} else if (getMedia() != null) {
													for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
														if (present_sub.matchCode(sub) || sub.equals("*")) {
															if (present_sub.getExternalFile() != null) {
																if (configuration.isAutoloadExternalSubtitles()) {
																	// Subtitle is external and we want external subtitles, look no further
																	matchedSub = present_sub;
																	LOGGER.trace("Matched external subtitles track: " + matchedSub);
																	break;
																} else {
																	// Subtitle is external but we do not want external subtitles, keep searching
																	LOGGER.trace("External subtitles ignored because of user setting: " + present_sub);
																}
															} else if (!matchedInternalSubtitles) {
																matchedSub = present_sub;
																LOGGER.trace("Matched internal subtitles track: " + matchedSub);
																if (configuration.isAutoloadExternalSubtitles()) {
																	// Subtitle is internal and we will wait to see if an external one is available instead
																	matchedInternalSubtitles = true;
																} else {
																	// Subtitle is internal and we will use it
																	break;
																}
															}
														}
													}
												}

												if (matchedSub != null && !matchedInternalSubtitles) {
													break;
												}
											}
										}
									}

									/**
									 * Check for external subtitles that were skipped in the above code block
									 * because they didn't match language preferences, if there wasn't already
									 * a match and the user settings specify it.
									 */
									if (matchedSub == null && configuration.isForceExternalSubtitles()) {
										for (DLNAMediaSubtitle present_sub : media.getSubtitleTracksList()) {
											if (present_sub.getExternalFile() != null) {
												matchedSub = present_sub;
												LOGGER.trace("Matched external subtitles track that did not match language preferences: " + matchedSub);
												break;
											}
										}
									}

									/**
									 * Disable chosen subtitles if the user has disabled all subtitles or
									 * if the language preferences have specified the "off" language.
									 *
									 * TODO: Can't we save a bunch of looping by checking for isDisableSubtitles
									 * just after the Live Subtitles check above?
									 */
									if (matchedSub != null && params.sid == null) {
										if (configuration.isDisableSubtitles() || (matchedSub.getLang() != null && matchedSub.getLang().equals("off"))) {
											LOGGER.trace("Disabled the subtitles: " + matchedSub);
										} else {
											params.sid = matchedSub;
											media_subtitle = params.sid;
										}
									}

									/**
									 * Check for forced subtitles.
									 */
									if (!configuration.isDisableSubtitles() && params.sid == null && media != null) {
										// Check for subtitles again
										File video = new File(getSystemName());
										FileUtil.isSubtitlesExists(video, media, false);

										if (configuration.isAutoloadExternalSubtitles()) {
											boolean forcedSubsFound = false;
											// Priority to external subtitles
											for (DLNAMediaSubtitle sub : media.getSubtitleTracksList()) {
												if (matchedSub != null && matchedSub.getLang() != null && matchedSub.getLang().equals("off")) {
													st = new StringTokenizer(configuration.getForcedSubtitleTags(), ",");

													while (sub.getSubtitlesTrackTitleFromMetadata() != null && st.hasMoreTokens()) {
														String forcedTags = st.nextToken();
														forcedTags = forcedTags.trim();
														if (
															sub.getSubtitlesTrackTitleFromMetadata().toLowerCase().contains(forcedTags) &&
															Iso639.isCodesMatching(sub.getLang(), configuration.getForcedSubtitleLanguage())
														) {
															LOGGER.trace("Forcing preferred subtitles: " + sub.getLang() + "/" + sub.getSubtitlesTrackTitleFromMetadata());
															LOGGER.trace("Forced subtitles track: " + sub);

															if (sub.getExternalFile() != null) {
																LOGGER.trace("Found external forced file: " + sub.getExternalFile().getAbsolutePath());
															}
															params.sid = sub;
															media_subtitle = params.sid;
															forcedSubsFound = true;
															break;
														}
													}

													if (forcedSubsFound == true) {
														break;
													}
												} else {
													LOGGER.trace("Found subtitles track: " + sub);
													if (sub.getExternalFile() != null) {
														LOGGER.trace("Found external file: " + sub.getExternalFile().getAbsolutePath());
														params.sid = sub;
														media_subtitle = params.sid;
														break;
													}
												}
											}
										}

										if (
											matchedSub != null &&
											matchedSub.getLang() != null &&
											matchedSub.getLang().equals("off")
										) {
											finishedMatchingPreferences = true;
										}

										if (!finishedMatchingPreferences && params.sid == null) {
											st = new StringTokenizer(UMSUtils.getLangList(params.mediaRenderer), ",");
											while (st.hasMoreTokens()) {
												String lang = st.nextToken();
												lang = lang.trim();
												LOGGER.trace("Looking for a subtitle track with lang: " + lang);
												for (DLNAMediaSubtitle sub : media.getSubtitleTracksList()) {
													if (
														sub.matchCode(lang) &&
														!(
															!configuration.isAutoloadExternalSubtitles() &&
															sub.getExternalFile() != null
														)
													) {
														params.sid = sub;
														LOGGER.trace("Matched subtitles track: " + params.sid);
														break;
													}
												}
											}
										}
									}
								}

								if (media_subtitle == null) {
									LOGGER.trace("We do not want a subtitle for " + getName());
								} else {
									LOGGER.trace("We do want a subtitle for " + getName());
								}
							}

							/**
							 * If:
							 * - There are no subtitles
							 * - This is not a DVD track
							 * - The media is muxable
							 * - The renderer accepts media muxed to MPEG-TS
							 * then the file is MPEG-TS
							 */
							if (
								media_subtitle == null &&
								!hasExternalSubtitles() &&
								media != null &&
								media.getDvdtrack() == 0 &&
								isMuxableResult &&
								mediaRenderer.isMuxH264MpegTS()
							) {
								isFileMPEGTS = true;
							}
						}

						if (isFileMPEGTS) {
							dlnaOrgPnFlags = "DLNA.ORG_PN=" + getMPEG_TS_SD_EU_ISOLocalizedValue(localizationValue);
							if (
								media.isH264() &&
								!VideoLanVideoStreaming.ID.equals(player.id()) &&
								isMuxableResult
							) {
								dlnaOrgPnFlags = "DLNA.ORG_PN=AVC_TS_HD_24_AC3_ISO";
								if (mediaRenderer.isTranscodeToMPEGTSH264AAC()) {
									dlnaOrgPnFlags = "DLNA.ORG_PN=AVC_TS_HP_HD_AAC";
								}
							}
						}
					} else if (media != null) {
						if (media.isMpegTS()) {
							dlnaOrgPnFlags = "DLNA.ORG_PN=" + getMPEG_TS_EULocalizedValue(localizationValue, media.isHDVideo());
							if (media.isH264()) {
								dlnaOrgPnFlags = "DLNA.ORG_PN=AVC_TS_HD_50_AC3";
								if (mediaRenderer.isTranscodeToMPEGTSH264AAC()) {
									dlnaOrgPnFlags = "DLNA.ORG_PN=AVC_TS_HP_HD_AAC";
								}
							}
						}
					}
				} else if (mime.equals("video/vnd.dlna.mpeg-tts")) {
					// patters - on Sony BDP m2ts clips aren't listed without this
					dlnaOrgPnFlags = "DLNA.ORG_PN=" + getMPEG_TS_EULocalizedValue(localizationValue, media.isHDVideo());
				} else if (mime.equals(JPEG_TYPEMIME)) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=JPEG_LRG";
				} else if (mime.equals(AUDIO_MP3_TYPEMIME)) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=MP3";
				} else if (mime.substring(0, 9).equals(AUDIO_LPCM_TYPEMIME) || mime.equals(AUDIO_WAV_TYPEMIME)) {
					dlnaOrgPnFlags = "DLNA.ORG_PN=LPCM";
				}
			}

			if (dlnaOrgPnFlags != null) {
				dlnaOrgPnFlags = "DLNA.ORG_PN=" + mediaRenderer.getDLNAPN(dlnaOrgPnFlags.substring(12));
			}
		}

		return dlnaOrgPnFlags;
	}

	/**
	 * Gets the media renderer's mime type if available, returns a default mime type otherwise.
	 *
	 * @param mediaRenderer
	 * 			Media Renderer for which to represent this information.
	 * @return String representation of the mime type
	 */
	private String getRendererMimeType(RendererConfiguration mediaRenderer) {
		// FIXME: There is a flaw here. In addChild(DLNAResource) the mime type
		// is determined for the default renderer. This renderer may rewrite the
		// mime type based on its configuration. Looking up that mime type is
		// not guaranteed to return a match for another renderer.
		String mime = mediaRenderer.getMimeType(mimeType());

		// Use our best guess if we have no valid mime type
		if (mime == null || mime.contains("/transcode")) {
			mime = HTTPResource.getDefaultMimeType(getType());
		}

		return mime;
	}

	/**
	 * @deprecated Use {@link #getDidlString(RendererConfiguration)} instead.
	 *
	 * @param mediaRenderer
	 * @return
	 */
	@Deprecated
	public final String toString(RendererConfiguration mediaRenderer) {
		return getDidlString(mediaRenderer);
	}

	/**
	 * Returns an XML (DIDL) representation of the DLNA node. It gives a
	 * complete representation of the item, with as many tags as available.
	 * Recommendations as per UPNP specification are followed where possible.
	 *
	 * @param mediaRenderer
	 *            Media Renderer for which to represent this information. Useful
	 *            for some hacks.
	 * @return String representing the item. An example would start like this:
	 *         {@code <container id="0$1" childCount="1" parentID="0" restricted="true">}
	 */
	public final String getDidlString(RendererConfiguration mediaRenderer) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(mediaRenderer);
		StringBuilder sb = new StringBuilder();
		boolean subsAreValidForStreaming = false;
		boolean xbox360 = mediaRenderer.isXbox360();
		if (!isFolder()) {
			if (format != null && format.isVideo()) {
				if (
					!configuration.isDisableSubtitles() &&
					player == null &&
					media_subtitle != null &&
					media_subtitle.isStreamable()
				) {
					subsAreValidForStreaming = true;
					LOGGER.trace("Setting subsAreValidForStreaming to true for " + media_subtitle.getExternalFile().getName());
				} else if (subsAreValidForStreaming) {
					LOGGER.trace("Not setting subsAreValidForStreaming and it is true for " + getName());
				} else {
					LOGGER.trace("Not setting subsAreValidForStreaming and it is false for " + getName());
				}
			}

			openTag(sb, "item");
		} else {
			openTag(sb, "container");
		}

		String id = getResourceId();
		if (xbox360) {
			// Ensure the xbox 360 doesn't confuse our ids with its own virtual folder ids.
			id += "$";
		}

		addAttribute(sb, "id", id);
		if (isFolder()) {
			if (!isDiscovered() && childrenNumber() == 0) {
				//  When a folder has not been scanned for resources, it will automatically have zero children.
				//  Some renderers like XBMC will assume a folder is empty when encountering childCount="0" and
				//  will not display the folder. By returning childCount="1" these renderers will still display
				//  the folder. When it is opened, its children will be discovered and childrenNumber() will be
				//  set to the right value.
				addAttribute(sb, "childCount", 1);
			} else {
				addAttribute(sb, "childCount", childrenNumber());
			}
		}

		id = getParentId();
		if (xbox360 && getFakeParentId() == null) {
			// Ensure the xbox 360 doesn't confuse our ids with its own virtual folder ids.
			id += "$";
		}

		addAttribute(sb, "parentID", id);
		addAttribute(sb, "restricted", "true");
		endTag(sb);
		StringBuilder wireshark = new StringBuilder();
		final DLNAMediaAudio firstAudioTrack = media != null ? media.getFirstAudioTrack() : null;

		/**
		 * Use the track title for audio files, otherwise use the filename.
		 */
		String title;
		if (
			firstAudioTrack != null &&
			StringUtils.isNotBlank(firstAudioTrack.getSongname()) &&
			getFormat() != null &&
			getFormat().isAudio()
		) {
			title = firstAudioTrack.getSongname() + (player != null && !configuration.isHideEngineNames() ? (" [" + player.name() + "]") : "");
		} else { // Ditlew - org
			title = (isFolder() || subsAreValidForStreaming) ? getDisplayName(null, false) : mediaRenderer.getUseSameExtension(getDisplayName(mediaRenderer, false));
		}

		title = resumeStr(title);
		addXMLTagAndAttribute(
			sb,
			"dc:title",
			encodeXML(mediaRenderer.getDcTitle(title, nameSuffix, this))
		);
		wireshark.append("\"").append(title).append("\"");
		if (firstAudioTrack != null) {
			if (StringUtils.isNotBlank(firstAudioTrack.getAlbum())) {
				addXMLTagAndAttribute(sb, "upnp:album", encodeXML(firstAudioTrack.getAlbum()));
			}

			if (StringUtils.isNotBlank(firstAudioTrack.getArtist())) {
				addXMLTagAndAttribute(sb, "upnp:artist", encodeXML(firstAudioTrack.getArtist()));
				addXMLTagAndAttribute(sb, "dc:creator", encodeXML(firstAudioTrack.getArtist()));
			}

			if (StringUtils.isNotBlank(firstAudioTrack.getGenre())) {
				addXMLTagAndAttribute(sb, "upnp:genre", encodeXML(firstAudioTrack.getGenre()));
			}

			if (firstAudioTrack.getTrack() > 0) {
				addXMLTagAndAttribute(sb, "upnp:originalTrackNumber", "" + firstAudioTrack.getTrack());
			}
		}

		if (!isFolder()) {
			int indexCount = 1;
			if (mediaRenderer.isDLNALocalizationRequired()) {
				indexCount = getDLNALocalesCount();
			}

			for (int c = 0; c < indexCount; c++) {
				openTag(sb, "res");
				addAttribute(sb, "xmlns:dlna", "urn:schemas-dlna-org:metadata-1-0/");
				String dlnaOrgPnFlags = getDlnaOrgPnFlags(mediaRenderer, c);
				String tempString = "http-get:*:" + getRendererMimeType(mediaRenderer) + ":" + (dlnaOrgPnFlags != null ? (dlnaOrgPnFlags + ";") : "") + getDlnaOrgOpFlags(mediaRenderer);
				wireshark.append(" ").append(tempString);
				addAttribute(sb, "protocolInfo", tempString);
				if (subsAreValidForStreaming && !mediaRenderer.useClosedCaption()) {
					addAttribute(sb, "pv:subtitleFileType", media_subtitle.getType().getExtension().toUpperCase());
					wireshark.append(" pv:subtitleFileType=").append(media_subtitle.getType().getExtension().toUpperCase());
					addAttribute(sb, "pv:subtitleFileUri", getSubsURL(media_subtitle));
					wireshark.append(" pv:subtitleFileUri=").append(getSubsURL(media_subtitle));
				}

				if (getFormat() != null && getFormat().isVideo() && media != null && media.isMediaparsed()) {
					if (player == null) {
						wireshark.append(" size=").append(media.getSize());
						addAttribute(sb, "size", media.getSize());
					} else {
						long transcoded_size = mediaRenderer.getTranscodedSize();
						if (transcoded_size != 0) {
							wireshark.append(" size=").append(transcoded_size);
							addAttribute(sb, "size", transcoded_size);
						}
					}

					if (media.getDuration() != null) {
						if (getSplitRange().isEndLimitAvailable()) {
							wireshark.append(" duration=").append(convertTimeToString(getSplitRange().getDuration(), DURATION_TIME_FORMAT));
							addAttribute(sb, "duration", convertTimeToString(getSplitRange().getDuration(), DURATION_TIME_FORMAT));
						} else {
							wireshark.append(" duration=").append(media.getDurationString());
							addAttribute(sb, "duration", media.getDurationString());
						}
					}

					if (media.getResolution() != null) {
						if (player != null && mediaRenderer.isKeepAspectRatio()) {
							addAttribute(sb, "resolution", getResolutionForKeepAR(media.getWidth(), media.getHeight()));
						} else {
							addAttribute(sb, "resolution", media.getResolution());
						}

					}

					addAttribute(sb, "bitrate", media.getRealVideoBitrate());
					if (firstAudioTrack != null) {
						if (firstAudioTrack.getAudioProperties().getNumberOfChannels() > 0) {
							addAttribute(sb, "nrAudioChannels", firstAudioTrack.getAudioProperties().getNumberOfChannels());
						}

						if (firstAudioTrack.getSampleFrequency() != null) {
							addAttribute(sb, "sampleFrequency", firstAudioTrack.getSampleFrequency());
						}
					}
				} else if (getFormat() != null && getFormat().isImage()) {
					if (media != null && media.isMediaparsed()) {
						wireshark.append(" size=").append(media.getSize());
						addAttribute(sb, "size", media.getSize());
						if (media.getResolution() != null) {
							addAttribute(sb, "resolution", media.getResolution());
						}
					} else {
						wireshark.append(" size=").append(length());
						addAttribute(sb, "size", length());
					}
				} else if (getFormat() != null && getFormat().isAudio()) {
					if (media != null && media.isMediaparsed()) {
						addAttribute(sb, "bitrate", media.getBitrate());
						if (media.getDuration() != null) {
							wireshark.append(" duration=").append(convertTimeToString(media.getDuration(), DURATION_TIME_FORMAT));
							addAttribute(sb, "duration", convertTimeToString(media.getDuration(), DURATION_TIME_FORMAT));
						}

						if (firstAudioTrack != null && firstAudioTrack.getSampleFrequency() != null) {
							addAttribute(sb, "sampleFrequency", firstAudioTrack.getSampleFrequency());
						}

						if (firstAudioTrack != null) {
							addAttribute(sb, "nrAudioChannels", firstAudioTrack.getAudioProperties().getNumberOfChannels());
						}

						if (player == null) {
							wireshark.append(" size=").append(media.getSize());
							addAttribute(sb, "size", media.getSize());
						} else {
							// Calculate WAV size
							if (firstAudioTrack != null) {
								int defaultFrequency = mediaRenderer.isTranscodeAudioTo441() ? 44100 : 48000;
								if (!configuration.isAudioResample()) {
									try {
										// FIXME: Which exception could be thrown here?
										defaultFrequency = firstAudioTrack.getSampleRate();
									} catch (Exception e) {
										LOGGER.debug("Caught exception", e);
									}
								}

								int na = firstAudioTrack.getAudioProperties().getNumberOfChannels();
								if (na > 2) { // No 5.1 dump in MPlayer
									na = 2;
								}

								int finalSize = (int) (media.getDurationInSeconds() * defaultFrequency * 2 * na);
								LOGGER.trace("Calculated size for " + getSystemName() + ": " + finalSize);
								wireshark.append(" size=").append(finalSize);
								addAttribute(sb, "size", finalSize);
							}
						}
					} else {
						wireshark.append(" size=").append(length());
						addAttribute(sb, "size", length());
					}
				} else {
					wireshark.append(" size=").append(DLNAMediaInfo.TRANS_SIZE).append(" duration=09:59:59");
					addAttribute(sb, "size", DLNAMediaInfo.TRANS_SIZE);
					addAttribute(sb, "duration", "09:59:59");
					addAttribute(sb, "bitrate", "1000000");
				}

				endTag(sb);
				// Add transcoded format extension to the output stream URL.
				String transcodedExtension = "";
				if (player != null && media != null) {
					// Note: Can't use instanceof below because the audio classes inherit the corresponding video class
					if (media.isVideo()) {
						if (mediaRenderer.isTranscodeToMPEGTS()) {
							transcodedExtension = "_transcoded_to.ts";
						} else if (mediaRenderer.isTranscodeToWMV() && !xbox360) {
							transcodedExtension = "_transcoded_to.wmv";
						} else {
							transcodedExtension = "_transcoded_to.mpg";
						}
					} else if (media.isAudio()) {
						if (mediaRenderer.isTranscodeToMP3()) {
							transcodedExtension = "_transcoded_to.mp3";
						} else if (mediaRenderer.isTranscodeToWAV()) {
							transcodedExtension = "_transcoded_to.wav";
						} else {
							transcodedExtension = "_transcoded_to.pcm";
						}
					}
				}

				wireshark.append(" ").append(getFileURL()).append(transcodedExtension);
				sb.append(getFileURL()).append(transcodedExtension);
				LOGGER.trace("Network debugger: " + wireshark.toString());
				wireshark.setLength(0);
				closeTag(sb, "res");
			}
		}

		if (subsAreValidForStreaming) {
			String subsURL = getSubsURL(media_subtitle);
			if (mediaRenderer.useClosedCaption()) {
				openTag(sb, "sec:CaptionInfoEx");
				addAttribute(sb, "sec:type", "srt");
				endTag(sb);
				sb.append(subsURL);
				closeTag(sb, "sec:CaptionInfoEx");
				LOGGER.trace("Network debugger: sec:CaptionInfoEx: sec:type=srt " + subsURL);
			} else {
				openTag(sb, "res");
				String format = media_subtitle.getType().getExtension();
				if (StringUtils.isBlank(format)) {
					format = "plain";
				}

				addAttribute(sb, "protocolInfo", "http-get:*:text/" + format + ":*");
				endTag(sb);
				sb.append(subsURL);
				closeTag(sb, "res");
				LOGGER.trace("Network debugger: http-get:*:text/" + format + ":*" + subsURL);
			}
		}

		if (!(isFolder() && !mediaRenderer.isSendFolderThumbnails())) {
			appendThumbnail(mediaRenderer, sb, "JPEG_TN");
			appendThumbnail(mediaRenderer, sb, "JPEG_SM");
		}

		if (getLastModified() > 0 && mediaRenderer.isSendDateMetadata()) {
			addXMLTagAndAttribute(sb, "dc:date", SDF_DATE.format(new Date(getLastModified())));
		}

		String uclass;
		if (first != null && media != null && !media.isSecondaryFormatValid()) {
			uclass = "dummy";
		} else {
			if (isFolder()) {
				uclass = "object.container.storageFolder";
				if (xbox360 && getFakeParentId() != null) {
					switch (getFakeParentId()) {
						case "7":
							uclass = "object.container.album.musicAlbum";
							break;
						case "6":
							uclass = "object.container.person.musicArtist";
							break;
						case "5":
							uclass = "object.container.genre.musicGenre";
							break;
						case "F":
							uclass = "object.container.playlistContainer";
							break;
					}
				}
			} else if (getFormat() != null && getFormat().isVideo()) {
				uclass = "object.item.videoItem";
			} else if (getFormat() != null && getFormat().isImage()) {
				uclass = "object.item.imageItem.photo";
			} else if (getFormat() != null && getFormat().isAudio()) {
				uclass = "object.item.audioItem.musicTrack";
			} else {
				uclass = "object.item.videoItem";
			}
		}

		addXMLTagAndAttribute(sb, "upnp:class", uclass);
		if (isFolder()) {
			closeTag(sb, "container");
		} else {
			closeTag(sb, "item");
		}

		return sb.toString();
	}

	/**
	 * Generate and append the response for the thumbnail based on the
	 * configuration of the renderer.
	 *
	 * @param mediaRenderer The renderer configuration.
	 * @param sb The StringBuilder to append the response to.
	 */
	private void appendThumbnail(RendererConfiguration mediaRenderer, StringBuilder sb, String format) {
		final String thumbURL = getThumbnailURL();
		if (StringUtils.isNotBlank(thumbURL)) {
			if (mediaRenderer.getThumbNailAsResource()) {
				// Samsung 2012 (ES and EH) models do not recognize the "albumArtURI" element. Instead,
				// the "res" element should be used.
				// Also use "res" when faking JPEG thumbs.
				openTag(sb, "res");

				if (getThumbnailContentType().equals(PNG_TYPEMIME) && !mediaRenderer.isForceJPGThumbnails()) {
					addAttribute(sb, "protocolInfo", "http-get:*:image/png:DLNA.ORG_PN=PNG_TN");
				} else {
					addAttribute(sb, "protocolInfo", "http-get:*:image/jpeg:DLNA.ORG_PN=" + format);
				}

				endTag(sb);
				sb.append(thumbURL);
				closeTag(sb, "res");
			} else {
				// Renderers that can handle the "albumArtURI" element.
				openTag(sb, "upnp:albumArtURI");
				addAttribute(sb, "xmlns:dlna", "urn:schemas-dlna-org:metadata-1-0/");

				if (getThumbnailContentType().equals(PNG_TYPEMIME) && !mediaRenderer.isForceJPGThumbnails()) {
					addAttribute(sb, "dlna:profileID", "PNG_TN");
				} else {
					addAttribute(sb, "dlna:profileID", format);
				}

				endTag(sb);
				sb.append(thumbURL);
				closeTag(sb, "upnp:albumArtURI");
			}
		}
	}

	private String getRequestId(String rendererId) {
		return String.format("%s|%x|%s", rendererId, hashCode(), getSystemName());
	}

	/**
	 * Plugin implementation. When this item is going to play, it will notify all the StartStopListener objects available.
	 *
	 * @see StartStopListener
	 */
	public void startPlaying(final String rendererId, final RendererConfiguration render) {
		final String requestId = getRequestId(rendererId);
		synchronized (requestIdToRefcount) {
			Integer temp = requestIdToRefcount.get(requestId);
			if (temp == null) {
				temp = 0;
			}

			final Integer refCount = temp;
			requestIdToRefcount.put(requestId, refCount + 1);
			if (refCount == 0) {
				final DLNAResource self = this;
				Runnable r = new Runnable() {
					@Override
					public void run() {
						InetAddress rendererIp;
						try {
							rendererIp = InetAddress.getByName(rendererId);
							RendererConfiguration renderer;
							if (render == null) {
								renderer = RendererConfiguration.getRendererConfigurationBySocketAddress(rendererIp);
							} else {
								renderer = render;
							}

							String rendererName = "unknown renderer";
							try {
								renderer.setPlayingRes(self);
								rendererName = renderer.getRendererName().replaceAll("\n", "");
							} catch (NullPointerException e) { }
							if (!quietPlay()) {
								LOGGER.info("Started playing " + getName() + " on your " + rendererName);
								LOGGER.debug("The full filename of which is: " + getSystemName() + " and the address of the renderer is: " + rendererId);
							}
						} catch (UnknownHostException ex) {
							LOGGER.debug("" + ex);
						}

						startTime = System.currentTimeMillis();
						for (final ExternalListener listener : ExternalFactory.getExternalListeners()) {
							if (listener instanceof StartStopListener) {
								// run these asynchronously for slow handlers (e.g. logging, scrobbling)
								Runnable fireStartStopEvent = new Runnable() {
									@Override
									public void run() {
										try {
											((StartStopListener) listener).nowPlaying(media, self);
										} catch (Throwable t) {
											LOGGER.error("Notification of startPlaying event failed for StartStopListener {}", listener.getClass(), t);
										}
									}
								};

								new Thread(fireStartStopEvent, "StartPlaying Event for " + listener.name()).start();
							}
						}
					}
				};

				new Thread(r, "StartPlaying Event").start();
			}
		}
	}

	/**
	 * Plugin implementation. When this item is going to stop playing, it will notify all the StartStopListener
	 * objects available.
	 *
	 * @see StartStopListener
	 */
	public void stopPlaying(final String rendererId, final RendererConfiguration render) {
		final DLNAResource self = this;
		final String requestId = getRequestId(rendererId);
		Runnable defer = new Runnable() {
			@Override
			public void run() {
				long start = startTime;
				try {
					Thread.sleep(STOP_PLAYING_DELAY);
				} catch (InterruptedException e) {
					LOGGER.error("stopPlaying sleep interrupted", e);
				}

				synchronized (requestIdToRefcount) {
					final Integer refCount = requestIdToRefcount.get(requestId);
					assert refCount != null;
					assert refCount > 0;
					requestIdToRefcount.put(requestId, refCount - 1);
					if (start != startTime) {
						return;
					}

					Runnable r = new Runnable() {
						@Override
						public void run() {
							if (refCount == 1) {
								requestIdToRefcount.put(requestId, 0);
								InetAddress rendererIp;
								try {
									rendererIp = InetAddress.getByName(rendererId);
									RendererConfiguration renderer;
									if (render == null) {
										renderer = RendererConfiguration.getRendererConfigurationBySocketAddress(rendererIp);
									} else {
										renderer = render;
									}

									String rendererName = "unknown renderer";
									try {
										// Reset only if another item hasn't already begun playing
										if (renderer.getPlayingRes() == self) {
											renderer.setPlayingRes(null);
										}
										rendererName = renderer.getRendererName();
									} catch (NullPointerException e) { }

									if (!quietPlay()) {
										LOGGER.info("Stopped playing " + getName() + " on your " + rendererName);
										LOGGER.debug("The full filename of which is: " + getSystemName() + " and the address of the renderer is: " + rendererId);
									}
								} catch (UnknownHostException ex) {
									LOGGER.debug("" + ex);
								}

								internalStop();
								for (final ExternalListener listener : ExternalFactory.getExternalListeners()) {
									if (listener instanceof StartStopListener) {
										// run these asynchronously for slow handlers (e.g. logging, scrobbling)
										Runnable fireStartStopEvent = new Runnable() {
											@Override
											public void run() {
												try {
													((StartStopListener) listener).donePlaying(media, self);
												} catch (Throwable t) {
													LOGGER.error("Notification of donePlaying event failed for StartStopListener {}", listener.getClass(), t);
												}
											}
										};

										new Thread(fireStartStopEvent, "StopPlaying Event for " + listener.name()).start();
									}
								}
							}
						}
					};

					new Thread(r, "StopPlaying Event").start();
				}
			}
		};

		new Thread(defer, "StopPlaying Event Deferrer").start();
	}

	/**
	 * Returns an InputStream of this DLNAResource that starts at a given time, if possible. Very useful if video chapters are being used.
	 *
	 * @param range
	 * @param mediarenderer
	 * @return The inputstream
	 * @throws IOException
	 */
	private long lastStart;

	public synchronized InputStream getInputStream(Range range, RendererConfiguration mediarenderer) throws IOException {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(mediarenderer);
		LOGGER.trace("Asked stream chunk : " + range + " of " + getName() + " and player " + player);

		// shagrath: small fix, regression on chapters
		boolean timeseek_auto = false;
		// Ditlew - WDTV Live
		// Ditlew - We convert byteoffset to timeoffset here. This needs the stream to be CBR!
		int cbr_video_bitrate = mediarenderer.getCBRVideoBitrate();
		long low = range.isByteRange() && range.isStartOffsetAvailable() ? range.asByteRange().getStart() : 0;
		long high = range.isByteRange() && range.isEndLimitAvailable() ? range.asByteRange().getEnd() : -1;
		Range.Time timeRange = range.createTimeRange();
		if (player != null && low > 0 && cbr_video_bitrate > 0) {
			int used_bit_rated = (int) ((cbr_video_bitrate + 256) * 1024 / (double) 8 * 1.04); // 1.04 = container overhead
			if (low > used_bit_rated) {
				timeRange.setStart(low / (double) (used_bit_rated));
				low = 0;

				// WDTV Live - if set to TS it asks multiple times and ends by
				// asking for an invalid offset which kills MEncoder
				if (timeRange.getStartOrZero() > media.getDurationInSeconds()) {
					return null;
				}

				// Should we rewind a little (in case our overhead isn't accurate enough)
				int rewind_secs = mediarenderer.getByteToTimeseekRewindSeconds();
				timeRange.rewindStart(rewind_secs);

				// shagrath:
				timeseek_auto = true;
			}
		}

		// Determine source of the stream
		if (player == null && !isResume()) {
			// No transcoding
			if (this instanceof IPushOutput) {
				PipedOutputStream out = new PipedOutputStream();
				InputStream fis = new PipedInputStream(out);
				((IPushOutput) this).push(out);

				if (low > 0) {
					fis.skip(low);
				}
				// http://www.ps3mediaserver.org/forum/viewtopic.php?f=11&t=12035

				return wrap(fis, high, low);
			}

			InputStream fis;
			if (getFormat() != null && getFormat().isImage() && media != null && media.getOrientation() > 1 && mediarenderer.isAutoRotateBasedOnExif()) {
				// seems it's a jpeg file with an orientation setting to take care of
				fis = ImagesUtil.getAutoRotateInputStreamImage(getInputStream(), media.getOrientation());
				if (fis == null) { // error, let's return the original one
					fis = getInputStream();
				}
			} else {
				fis = getInputStream();
			}

			if (fis != null) {
				if (low > 0) {
					fis.skip(low);
				}

				// http://www.ps3mediaserver.org/forum/viewtopic.php?f=11&t=12035
				fis = wrap(fis, high, low);
				if (timeRange.getStartOrZero() > 0 && this instanceof RealFile) {
					fis.skip(MpegUtil.getPositionForTimeInMpeg(((RealFile) this).getFile(), (int) timeRange.getStartOrZero()));
				}
			}

			return fis;
		} else {
			// Pipe transcoding result
			OutputParams params = new OutputParams(configuration);
			params.aid = getMediaAudio();
			params.sid = media_subtitle;
			params.header = getHeaders();
			params.mediaRenderer = mediarenderer;
			timeRange.limit(getSplitRange());
			params.timeseek = timeRange.getStartOrZero();
			params.timeend = timeRange.getEndOrZero();
			params.shift_scr = timeseek_auto;
			if (this instanceof IPushOutput) {
				params.stdin = (IPushOutput) this;
			}

			if (resume != null) {
				if (range.isTimeRange()) {
					resume.update((Range.Time) range, this);
				}

				params.timeseek = resume.getTimeOffset() / 1000;
				if (player == null) {
					player = new FFMpegVideo();
				}
			}

			if (System.currentTimeMillis() - lastStart < 500) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					LOGGER.error(null, e);
				}
			}

			// (Re)start transcoding process if necessary
			if (externalProcess == null || externalProcess.isDestroyed()) {
				// First playback attempt => start new transcoding process
				LOGGER.debug("Starting transcode/remux of " + getName() + " with media info: " + media);
				lastStart = System.currentTimeMillis();
				externalProcess = player.launchTranscode(this, media, params);
				if (params.waitbeforestart > 0) {
					LOGGER.trace("Sleeping for {} milliseconds", params.waitbeforestart);
					try {
						Thread.sleep(params.waitbeforestart);
					} catch (InterruptedException e) {
						LOGGER.error(null, e);
					}

					LOGGER.trace("Finished sleeping for " + params.waitbeforestart + " milliseconds");
				}
			} else if (
				params.timeseek > 0 &&
				media != null &&
				media.isMediaparsed() &&
				media.getDurationInSeconds() > 0
			) {
				// Time seek request => stop running transcode process and start a new one
				LOGGER.debug("Requesting time seek: " + params.timeseek + " seconds");
				params.minBufferSize = 1;
				Runnable r = new Runnable() {
					@Override
					public void run() {
						externalProcess.stopProcess();
					}
				};

				new Thread(r, "External Process Stopper").start();
				lastStart = System.currentTimeMillis();
				ProcessWrapper newExternalProcess = player.launchTranscode(this, media, params);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					LOGGER.error(null, e);
				}

				if (newExternalProcess == null) {
					LOGGER.trace("External process instance is null... sounds not good");
				}

				externalProcess = newExternalProcess;
			}

			if (externalProcess == null) {
				return null;
			}

			InputStream is = null;
			int timer = 0;
			while (is == null && timer < 10) {
				is = externalProcess.getInputStream(low);
				timer++;
				if (is == null) {
					LOGGER.debug("External input stream instance is null... sounds not good, waiting 500ms");
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
					}
				}
			}

			// fail fast: don't leave a process running indefinitely if it's
			// not producing output after params.waitbeforestart milliseconds + 5 seconds
			// this cleans up lingering MEncoder web video transcode processes that hang
			// instead of exiting
			if (is == null && !externalProcess.isDestroyed()) {
				Runnable r = new Runnable() {
					@Override
					public void run() {
						LOGGER.error("External input stream instance is null... stopping process");
						externalProcess.stopProcess();
					}
				};

				new Thread(r, "Hanging External Process Stopper").start();
			}

			return is;
		}
	}

	/**
	 * Wrap an {@link InputStream} in a {@link SizeLimitInputStream} that sets a
	 * limit to the maximum number of bytes to be read from the original input
	 * stream. The number of bytes is determined by the high and low value
	 * (bytes = high - low). If the high value is less than the low value, the
	 * input stream is not wrapped and returned as is.
	 *
	 * @param input
	 *            The input stream to wrap.
	 * @param high
	 *            The high value.
	 * @param low
	 *            The low value.
	 * @return The resulting input stream.
	 */
	private InputStream wrap(InputStream input, long high, long low) {
		if (input != null && high > low) {
			long bytes = (high - (low < 0 ? 0 : low)) + 1;
			LOGGER.trace("Using size-limiting stream (" + bytes + " bytes)");
			return new SizeLimitInputStream(input, bytes);
		}

		return input;
	}

	public String mimeType() {
		return mimeType(player);
	}

	public String mimeType(Player player) {
		if (player != null) {
			// FIXME: This cannot be right. A player like FFmpeg can output many
			// formats depending on the media and the renderer. Also, players are
			// singletons. Therefore it is impossible to have exactly one mime
			// type to return.
			return player.mimeType();
		} else if (media != null && media.isMediaparsed()) {
			return media.getMimeType();
		} else if (getFormat() != null) {
			return getFormat().mimeType();
		} else {
			return getDefaultMimeType(getSpecificType());
		}
	}

	/**
	 * Prototype function. Original comment: need to override if some thumbnail work is to be done when mediaparserv2 enabled
	 */
	public void checkThumbnail() {
		// need to override if some thumbnail work is to be done when mediaparserv2 enabled
	}

	@Deprecated
	protected void checkThumbnail(InputFile inputFile) {
		checkThumbnail(inputFile, null);
	}

	/**
	 * Checks if a thumbnail exists, and, if not, generates one (if possible).
	 * Called from Request/RequestV2 in response to thumbnail requests e.g. HEAD /get/0$1$0$42$3/thumbnail0000%5BExample.mkv
	 * Calls DLNAMediaInfo.generateThumbnail, which in turn calls DLNAMediaInfo.parse.
	 *
	 * @param inputFile File to check or generate the thumbnail for.
	 * @param renderer The renderer profile
	 */
	protected void checkThumbnail(InputFile inputFile, RendererConfiguration renderer) {
		// Use device-specific pms conf, if any
		PmsConfiguration configuration = PMS.getConfiguration(renderer);
		if (media != null && (!media.isThumbready() || MediaMonitor.isWatched(inputFile.getFile().getAbsolutePath())) && configuration.isThumbnailGenerationEnabled()) {
			Double seekPosition = (double) configuration.getThumbnailSeekPos();
			if (isResume()) {
				Double resumePosition = (double) (resume.getTimeOffset() / 1000);

				if (media.getDurationInSeconds() > 0 && resumePosition < media.getDurationInSeconds()) {
					seekPosition = resumePosition;
				}
			}

			media.generateThumbnail(inputFile, getFormat(), getType(), seekPosition, isResume(), renderer);
			if (!isResume() && media.getThumb() != null && configuration.getUseCache() && inputFile.getFile() != null) {
				PMS.get().getDatabase().updateThumbnail(inputFile.getFile().getAbsolutePath(), inputFile.getFile().lastModified(), getType(), media);
			}
		}
	}

	/**
	 * Returns the input stream for this resource's generic thumbnail,
	 * which is the first of:
	 *          - its Format icon, if any
	 *          - the fallback image, if any
	 *          - the default video icon
	 *
	 * @param fallback
	 *            the fallback image, or null.
	 *
	 * @return The InputStream
	 * @throws IOException
	 */
	public InputStream getGenericThumbnailInputStream(String fallback) throws IOException {
		String thumb = fallback;
		if (format != null && format.getIcon() != null) {
			thumb = format.getIcon();
		}

		// Thumb could be:
		if (thumb != null && isCodeValid(this)) {
			// A local file
			if (new File(thumb).exists()) {
				return new FileInputStream(thumb);
			}

			// A jar resource
			InputStream is;
			if ((is = getResourceInputStream(thumb)) != null) {
				return is;
			}

			// A URL
			try {
				return downloadAndSend(thumb, true);
			} catch (Exception e) {}
		}

		// Or none of the above
		String defaultThumbnailImage = "images/thumbnail-video-256.png";
		if (isFolder()) {
			defaultThumbnailImage = "images/thumbnail-folder-256.png";
			if (defaultRenderer != null && defaultRenderer.isForceJPGThumbnails()) {
				defaultThumbnailImage = "images/thumbnail-folder-120.jpg";
			}
		} else if (defaultRenderer != null && defaultRenderer.isForceJPGThumbnails()) {
			defaultThumbnailImage = "images/thumbnail-video-120.jpg";
		}

		LOGGER.debug("use def thumb " + defaultThumbnailImage);
		return getResourceInputStream(defaultThumbnailImage);
	}

	/**
	 * Returns the input stream for this resource's thumbnail
	 * (or a default image if a thumbnail can't be found).
	 * Typically overridden by a subclass.
	 *
	 * @return The InputStream
	 * @throws IOException
	 */
	public InputStream getThumbnailInputStream() throws IOException {
		String id = null;
		if (media_audio != null) {
			id = media_audio.getLang();
		}

		if (media_subtitle != null && media_subtitle.getId() != -1) {
			id = media_subtitle.getLang();
		}

		if ((media_subtitle != null || media_audio != null) && StringUtils.isBlank(id)) {
			id = DLNAMediaLang.UND;
		}

		if (id != null) {
			String code = Iso639.getISO639_2Code(id.toLowerCase());
			return getResourceInputStream("/images/codes/" + code + ".png");
		}

		if (isAvisynth()) {
			return getResourceInputStream("/images/logo-avisynth.png");
		}

		return getGenericThumbnailInputStream(null);
	}

	public String getThumbnailContentType() {
		return HTTPResource.JPEG_TYPEMIME;
	}

	public int getType() {
		if (getFormat() != null) {
			return getFormat().getType();
		} else {
			return Format.UNKNOWN;
		}
	}

	/**
	 * Prototype function.
	 *
	 * @return true if child can be added to other folder.
	 * @see #addChild(DLNAResource)
	 */
	public abstract boolean isValid();

	public boolean allowScan() {
		return false;
	}

	/**
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		result.append(getClass().getSimpleName());
		result.append(" [id=");
		result.append(id);
		result.append(", name=");
		result.append(getName());
		result.append(", full path=");
		result.append(getResourceId());
		result.append(", ext=");
		result.append(format);
		result.append(", discovered=");
		result.append(isDiscovered());
		result.append("]");
		return result.toString();
	}

	/**
	 * Returns the specific type of resource. Valid types are defined in {@link Format}.
	 *
	 * @return The specific type
	 */
	protected int getSpecificType() {
		return specificType;
	}

	/**
	 * Set the specific type of this resource. Valid types are defined in {@link Format}.
	 *
	 * @param specificType The specific type to set.
	 */
	protected void setSpecificType(int specificType) {
		this.specificType = specificType;
	}

	/**
	 * Returns the {@link Format} of this resource, which defines its capabilities.
	 *
	 * @return The format of this resource.
	 */
	public Format getFormat() {
		return format;
	}

	/**
	 * Sets the {@link Format} of this resource, thereby defining its capabilities.
	 *
	 * @param format The format to set.
	 */
	public void setFormat(Format format) {
		this.format = format;

		// Set deprecated variable for backwards compatibility
		ext = format;
	}

	/**
	 * @deprecated Use {@link #getFormat()} instead.
	 *
	 * @return The format of this resource.
	 */
	@Deprecated
	public Format getExt() {
		return getFormat();
	}

	/**
	 * @deprecated Use {@link #setFormat(Format)} instead.
	 *
	 * @param format The format to set.
	 */
	@Deprecated
	protected void setExt(Format format) {
		setFormat(format);
	}

	/**
	 * Returns the {@link DLNAMediaInfo} object for this resource, containing the
	 * specifics of this resource, e.g. the duration.
	 *
	 * @return The object containing detailed information.
	 */
	public DLNAMediaInfo getMedia() {
		return media;
	}

	/**
	 * Sets the the {@link DLNAMediaInfo} object that contains all specifics for
	 * this resource.
	 *
	 * @param media The object containing detailed information.
	 * @since 1.50
	 */
	public void setMedia(DLNAMediaInfo media) {
		this.media = media;
	}

	/**
	 * Returns the {@link DLNAMediaAudio} object for this resource that contains
	 * the audio specifics. A resource can have many audio tracks, this method
	 * returns the one that should be played.
	 *
	 * @return The audio object containing detailed information.
	 * @since 1.50
	 */
	public DLNAMediaAudio getMediaAudio() {
		return media_audio;
	}

	/**
	 * Sets the {@link DLNAMediaAudio} object for this resource that contains
	 * the audio specifics. A resource can have many audio tracks, this method
	 * determines the one that should be played.
	 *
	 * @param mediaAudio The audio object containing detailed information.
	 * @since 1.50
	 */
	protected void setMediaAudio(DLNAMediaAudio mediaAudio) {
		this.media_audio = mediaAudio;
	}

	/**
	 * Returns the {@link DLNAMediaSubtitle} object for this resource that
	 * contains the specifics for the subtitles. A resource can have many
	 * subtitles, this method returns the one that should be displayed.
	 *
	 * @return The subtitle object containing detailed information.
	 * @since 1.50
	 */
	public DLNAMediaSubtitle getMediaSubtitle() {
		return media_subtitle;
	}

	/**
	 * Sets the {@link DLNAMediaSubtitle} object for this resource that
	 * contains the specifics for the subtitles. A resource can have many
	 * subtitles, this method determines the one that should be used.
	 *
	 * @param mediaSubtitle The subtitle object containing detailed information.
	 * @since 1.50
	 */
	public void setMediaSubtitle(DLNAMediaSubtitle mediaSubtitle) {
		this.media_subtitle = mediaSubtitle;
	}

	/**
	 * @deprecated Use {@link #getLastModified()} instead.
	 *
	 * Returns the timestamp at which this resource was last modified.
	 *
	 * @return The timestamp.
	 */
	@Deprecated
	public long getLastmodified() {
		return getLastModified();
	}

	/**
	 * Returns the timestamp at which this resource was last modified.
	 *
	 * @return The timestamp.
	 * @since 1.71.0
	 */
	public long getLastModified() {
		return lastmodified; // TODO rename lastmodified -> lastModified
	}

	/**
	 * @deprecated Use {@link #setLastModified(long)} instead.
	 *
	 * Sets the timestamp at which this resource was last modified.
	 *
	 * @param lastModified The timestamp to set.
	 * @since 1.50
	 */
	@Deprecated
	protected void setLastmodified(long lastModified) {
		setLastModified(lastModified);
	}

	/**
	 * Sets the timestamp at which this resource was last modified.
	 *
	 * @param lastModified The timestamp to set.
	 * @since 1.71.0
	 */
	protected void setLastModified(long lastModified) {
		this.lastmodified = lastModified; // TODO rename lastmodified -> lastModified
	}

	/**
	 * Returns the {@link Player} object that is used to encode this resource
	 * for the renderer. Can be null.
	 *
	 * @return The player object.
	 */
	public Player getPlayer() {
		return player;
	}

	/**
	 * Sets the {@link Player} object that is to be used to encode this
	 * resource for the renderer. The player object can be null.
	 *
	 * @param player The player object to set.
	 * @since 1.50
	 */
	public void setPlayer(Player player) {
		this.player = player;
	}

	/**
	 * Returns true when the details of this resource have already been
	 * investigated. This helps is not doing the same work twice.
	 *
	 * @return True if discovered, false otherwise.
	 */
	public boolean isDiscovered() {
		return discovered;
	}

	/**
	 * Set to true when the details of this resource have already been
	 * investigated. This helps is not doing the same work twice.
	 *
	 * @param discovered Set to true if this resource is discovered,
	 * 			false otherwise.
	 * @since 1.50
	 */
	protected void setDiscovered(boolean discovered) {
		this.discovered = discovered;
	}

	/**
	 * @Deprecated use {@link #hasExternalSubtitles()} instead
	 */
	@Deprecated
	protected boolean isSrtFile() {
		return hasExternalSubtitles();
	}

	/**
	 * @Deprecated use {@link #hasExternalSubtitles()} instead
	 */
	@Deprecated
	protected boolean isSubsFile() {
		return hasExternalSubtitles();
	}

	/**
	 * Whether this resource has external subtitles.
	 *
	 * @return whether this resource has external subtitles
	 */
	protected boolean hasExternalSubtitles() {
		return hasExternalSubtitles;
	}

	/**
	 * @Deprecated use {@link #setHasExternalSubtitles(boolean)} instead
	 */
	@Deprecated
	protected void setSrtFile(boolean srtFile) {
		setHasExternalSubtitles(srtFile);
	}

	/**
	 * @Deprecated use {@link #setHasExternalSubtitles(boolean)} instead
	 */
	@Deprecated
	protected void setSubsFile(boolean srtFile) {
		setHasExternalSubtitles(srtFile);
	}

	/**
	 * Sets whether this resource has external subtitles.
	 *
	 * @param value whether this resource has external subtitles
	 */
	protected void setHasExternalSubtitles(boolean value) {
		this.hasExternalSubtitles = value;
	}

	/**
	 * Returns the update counter for this resource. When the resource needs
	 * to be refreshed, its counter is updated.
	 *
	 * @return The update counter.
	 * @see #notifyRefresh()
	 */
	public int getUpdateId() {
		return updateId;
	}

	/**
	 * Sets the update counter for this resource. When the resource needs
	 * to be refreshed, its counter should be updated.
	 *
	 * @param updateId The counter value to set.
	 * @since 1.50
	 */
	protected void setUpdateId(int updateId) {
		this.updateId = updateId;
	}

	/**
	 * Returns the update counter for all resources. When all resources need
	 * to be refreshed, this counter is updated.
	 *
	 * @return The system update counter.
	 * @since 1.50
	 */
	public static int getSystemUpdateId() {
		return systemUpdateId;
	}

	/**
	 * Sets the update counter for all resources. When all resources need
	 * to be refreshed, this counter should be updated.
	 *
	 * @param systemUpdateId The system update counter to set.
	 * @since 1.50
	 */
	public static void setSystemUpdateId(int systemUpdateId) {
		DLNAResource.systemUpdateId = systemUpdateId;
	}

	/**
	 * Returns whether or not this is a nameless resource.
	 *
	 * @return True if the resource is nameless.
	 */
	public boolean isNoName() {
		return noName;
	}

	/**
	 * Sets whether or not this is a nameless resource. This is particularly
	 * useful in the virtual TRANSCODE folder for a file, where the same file
	 * is copied many times with different audio and subtitle settings. In that
	 * case the name of the file becomes irrelevant and only the settings
	 * need to be shown.
	 *
	 * @param noName Set to true if the resource is nameless.
	 * @since 1.50
	 */
	protected void setNoName(boolean noName) {
		this.noName = noName;
	}

	/**
	 * Returns the from - to time range for this resource.
	 *
	 * @return The time range.
	 */
	public Range.Time getSplitRange() {
		return splitRange;
	}

	/**
	 * Sets the from - to time range for this resource.
	 *
	 * @param splitRange The time range to set.
	 * @since 1.50
	 */
	protected void setSplitRange(Range.Time splitRange) {
		this.splitRange = splitRange;
	}

	/**
	 * Returns the number of the track to split from this resource.
	 *
	 * @return the splitTrack
	 * @since 1.50
	 */
	protected int getSplitTrack() {
		return splitTrack;
	}

	/**
	 * Sets the number of the track from this resource to split.
	 *
	 * @param splitTrack The track number.
	 * @since 1.50
	 */
	protected void setSplitTrack(int splitTrack) {
		this.splitTrack = splitTrack;
	}

	/**
	 * Returns the default renderer configuration for this resource.
	 *
	 * @return The default renderer configuration.
	 * @since 1.50
	 */
	public RendererConfiguration getDefaultRenderer() {
		return defaultRenderer;
	}

	/**
	 * Sets the default renderer configuration for this resource.
	 *
	 * @param defaultRenderer The default renderer configuration to set.
	 * @since 1.50
	 */
	public void setDefaultRenderer(RendererConfiguration defaultRenderer) {
		this.defaultRenderer = defaultRenderer;
		configuration = PMS.getConfiguration(defaultRenderer);
	}

	/**
	 * Returns whether or not this resource is handled by AviSynth.
	 *
	 * @return True if handled by AviSynth, otherwise false.
	 * @since 1.50
	 */
	protected boolean isAvisynth() {
		return avisynth;
	}

	/**
	 * Sets whether or not this resource is handled by AviSynth.
	 *
	 * @param avisynth Set to true if handled by Avisyth, otherwise false.
	 * @since 1.50
	 */
	protected void setAvisynth(boolean avisynth) {
		this.avisynth = avisynth;
	}

	/**
	 * Returns true if transcoding should be skipped for this resource.
	 *
	 * @return True if transcoding should be skipped, false otherwise.
	 * @since 1.50
	 */
	protected boolean isSkipTranscode() {
		return skipTranscode;
	}

	/**
	 * Set to true if transcoding should be skipped for this resource.
	 *
	 * @param skipTranscode Set to true if trancoding should be skipped, false
	 * 			otherwise.
	 * @since 1.50
	 */
	protected void setSkipTranscode(boolean skipTranscode) {
		this.skipTranscode = skipTranscode;
	}

	/**
	 * Returns the list of children for this resource.
	 *
	 * @return List of children objects.
	 */
	public List<DLNAResource> getChildren() {
		return children;
	}

	/**
	 * Sets the list of children for this resource.
	 *
	 * @param children The list of children to set.
	 * @since 1.50
	 */
	protected void setChildren(List<DLNAResource> children) {
		this.children = (DLNAList) children;
	}

	/**
	 * @deprecated use {@link #getLastChildId()} instead.
	 */
	@Deprecated
	protected int getLastChildrenId() {
		return getLastChildId();
	}

	/**
	 * Returns the numerical ID of the last child added.
	 *
	 * @return The ID.
	 * @since 1.80.0
	 */
	protected int getLastChildId() {
		return lastChildrenId;
	}

	/**
	 * @deprecated use {@link #setLastChildId(int)} instead.
	 */
	@Deprecated
	protected void setLastChildrenId(int lastChildId) {
		setLastChildId(lastChildId);
	}

	/**
	 * Sets the numerical ID of the last child added.
	 *
	 * @param lastChildId The ID to set.
	 * @since 1.80.0
	 */
	protected void setLastChildId(int lastChildId) {
		this.lastChildrenId = lastChildId;
	}

	/**
	 * Returns the timestamp when this resource was last refreshed.
	 *
	 * @return The timestamp.
	 */
	long getLastRefreshTime() {
		return lastRefreshTime;
	}

	/**
	 * Sets the timestamp when this resource was last refreshed.
	 *
	 * @param lastRefreshTime The timestamp to set.
	 * @since 1.50
	 */
	protected void setLastRefreshTime(long lastRefreshTime) {
		this.lastRefreshTime = lastRefreshTime;
	}

	private static final int DEPTH_WARNING_LIMIT = 7;

	private boolean depthLimit() {
		DLNAResource tmp = this;
		int depth = 0;
		while (tmp != null) {
			tmp = tmp.parent;
			depth++;
		}

		return (depth > DEPTH_WARNING_LIMIT);
	}

	public boolean isSearched() {
		return false;
	}

	public byte[] getHeaders() {
		return null;
	}

	public void attach(String key, Object data) {
		if (attachments == null) {
			attachments = new HashMap<>();
		}

		attachments.put(key, data);
	}

	public Object getAttachment(String key) {
		return attachments == null ? null : attachments.get(key);
	}

	public boolean isURLResolved() {
		return false;
	}

	////////////////////////////////////////////////////
	// Subtitle handling
	////////////////////////////////////////////////////

	private SubSelect getSubSelector(boolean create) {
		if (
			configuration.isDisableSubtitles() ||
			!configuration.isAutoloadExternalSubtitles() ||
			configuration.isHideLiveSubtitlesFolder()
		) {
			return null;
		}

		// Search for transcode folder
		for (DLNAResource r : children) {
			if (r instanceof SubSelect) {
				return (SubSelect) r;
			}
		}

		if (create) {
			SubSelect vf = new SubSelect();
			addChildInternal(vf);
			return vf;
		}

		return null;
	}

	public boolean isSubSelectable() {
		return false;
	}

	////////////////////////////////////////////////////
	// Resume handling
	////////////////////////////////////////////////////

	private ResumeObj resume;
	private int resHash;
	private long startTime;

	private void internalStop() {
		DLNAResource res = resumeStop();
		final RootFolder root = ((defaultRenderer != null) ? defaultRenderer.getRootFolder() : null);
		if (root != null) {
			if (res == null) {
				res = this.clone();
			} else {
				res = res.clone();
			}

			root.stopPlaying(res);
		}
	}

	public int resumeHash() {
		return resHash;
	}

	public ResumeObj getResume() {
		return resume;
	}

	public void setResume(ResumeObj r) {
		resume = r;
	}

	public boolean isResumeable() {
		if (format != null) {
			// Only resume videos
			return format.isVideo();
		}

		return true;
	}

	private DLNAResource resumeStop() {
		if (!configuration.isResumeEnabled() || !isResumeable()) {
			return null;
		}

		notifyRefresh();
		if (resume != null) {
			resume.stop(startTime, (long) (media.getDurationInSeconds() * 1000));
			if (resume.isDone()) {
				parent.getChildren().remove(this);
			} else if (getMedia() != null) {
				media.setThumbready(false);
			}
		} else {
			for (DLNAResource res : parent.getChildren()) {
				if (res.isResume() && res.getName().equals(getName())) {
					res.resume.stop(startTime, (long) (media.getDurationInSeconds() * 1000));
					if (res.resume.isDone()) {
						parent.getChildren().remove(res);
						return null;
					}

					if (res.getMedia() != null) {
						res.media.setThumbready(false);
					}

					return res;
				}
			}

			ResumeObj r = ResumeObj.store(this, startTime);
			if (r != null) {
				DLNAResource clone = this.clone();
				clone.resume = r;
				clone.resHash = resHash;
				if (clone.media != null) {
					clone.media.setThumbready(false);
				}

				clone.player = player;
				parent.addChildInternal(clone);
				return clone;
			}
		}

		return null;
	}

	public final boolean isResume() {
		return isResumeable() && (resume != null);
	}

	public int minPlayTime() {
		return configuration.getMinPlayTime();
	}

	private String resumeStr(String s) {
		if (isResume()) {
			return Messages.getString("PMS.134") + ": " + s;
		} else {
			return s;
		}
	}

	public String resumeName() {
		return resumeStr(getDisplayName());
	}

	/**
	 * Handle serialization.
	 *
	 * This method should be overridden by all media types that can be
	 * bookmarked, i.e. serialized to an external file.
	 * By default it just returns null which means the resource is ignored
	 * when serializing.
	 */
	public String write() {
		return null;
	}

	private ExternalListener masterParent;

	public void setMasterParent(ExternalListener r) {
		if (masterParent == null) {
			// If master is already set ignore this
			masterParent = r;
		}
	}

	public ExternalListener getMasterParent() {
		return masterParent;
	}

	// Returns the index of the given child resource id, or -1 if not found
	public int indexOf(String objectId) {
		// Use the index id string only, omitting any trailing filename
		String resourceId = StringUtils.substringBefore(objectId, "/");
		if (resourceId != null) {
			for (int i = 0; i < children.size(); i++) {
				if (resourceId.equals(children.get(i).getResourceId())) {
					return i;
				}
			}
		}

		return -1;
	}

	// Attempts to automatically create the appropriate container for
	// the given uri. Defaults to mpeg video for indeterminate local uris.
	public static DLNAResource autoMatch(String uri, String name) {
		try {
			uri = URLDecoder.decode(uri, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			LOGGER.error("URL decoding error ", e);
		}

		boolean isweb = uri.matches("\\S+://.+");
		Format f = FormatFactory.getAssociatedFormat(isweb ? "." + FileUtil.getUrlExtension(uri) : uri);
		int type = f == null ? Format.VIDEO : f.getType();
		if (name == null) {
			name = new File(StringUtils.substringBefore(uri, "?")).getName();
		}

		DLNAResource d = isweb ?
			type == Format.VIDEO ? new WebVideoStream(name, uri, null) :
			type == Format.AUDIO ? new WebAudioStream(name, uri, null) :
			type == Format.IMAGE ? new FeedItem(name, uri, null, null, Format.IMAGE) : null
			:
			new RealFile(new File(uri));
		if (f == null && !isweb) {
			d.setFormat(FormatFactory.getAssociatedFormat(".mpg"));
		}

		LOGGER.debug(d == null ?
			("Could not auto-match " + uri) :
			("Created auto-matched container: "+ d));
		return d;
	}

	// A general-purpose free-floating folder
	public static class unattachedFolder extends VirtualFolder {
		public unattachedFolder(String name) {
			super(name, null);
			setId(name);
		}

		public DLNAResource add(DLNAResource d) {
			if (d != null) {
				addChild(d);
				d.setId(d.getId() + "$" + getId());
				return d;
			}

			return null;
		}

		public DLNAResource add(String uri, String name, RendererConfiguration r) {
			DLNAResource  d = autoMatch(uri, name);
			if (d != null) {
				// Set the auto-matched item's renderer
				d.setDefaultRenderer(r);
				// Cache our previous renderer and
				// pretend to be a parent with the same renderer
				RendererConfiguration prev = getDefaultRenderer();
				setDefaultRenderer(r);
				// Now add the item and resolve its rendering details
				add(d);
				d.resolve();
				// Restore our previous renderer
				setDefaultRenderer(prev);
			}

			return d;
		}

		public int getIndex(String objectId) {
			return getIndex(objectId, null);
		}

		public int getIndex(String objectId, RendererConfiguration r) {
			int index = indexOf(objectId);
			if (index == -1 && r != null) {
				index = indexOf(recreate(objectId, null, r).getResourceId());
			}

			return index;
		}

		public DLNAResource get(String objectId, RendererConfiguration r) {
			int index = getIndex(objectId, r);
			DLNAResource d = index > -1 ? getChildren().get(index) : null;
			if (d != null && r != null && ! r.equals(d.getDefaultRenderer())) {
				d.updateRendering(r);
			}

			return d;
		}

		public List<DLNAResource> asList(String objectId) {
			int index = getIndex(objectId);
			return index > -1 ? getChildren().subList(index, index + 1) : null;
		}

		// Try to recreate a lost item from a previous session
		// using its objectId's trailing uri, if any

		public DLNAResource recreate(String objectId, String name, RendererConfiguration r) {
			try {
				return add(StringUtils.substringAfter(objectId, "/"), name, r);
			} catch (Exception e) {
				return null;
			}
		}
	}

	// A temp folder for non-xmb items
	public static final unattachedFolder Temp = new unattachedFolder("Temp");

	// Returns whether the url appears to be ours
	public static boolean isResourceUrl(String url) {
		return url != null && url.startsWith(PMS.get().getServer().getURL() + "/get/");
	}

	// Returns the url's resourceId (i.e. index without trailing filename) if any or null
	public static String parseResourceId(String url) {
		return isResourceUrl(url) ? StringUtils.substringBetween(url + "/", "get/", "/") : null;
	}

	// Returns the url's objectId (i.e. index including trailing filename) if any or null
	public static String parseObjectId(String url) {
		return isResourceUrl(url) ? StringUtils.substringAfter(url, "get/") : null;
	}

	// Returns the DLNAResource pointed to by the uri if it exists
	// or else a new Temp item (or null)
	public static DLNAResource getValidResource(String uri, String name, RendererConfiguration r) {
		LOGGER.debug("Validating uri " + uri);
		String objectId = parseObjectId(uri);
		if (objectId != null) {
			if (objectId.startsWith("Temp$")) {
				int index = Temp.indexOf(objectId);
				return index > -1 ? Temp.getChildren().get(index) : Temp.recreate(objectId, name, r);
			} else {
				if (r == null) {
					r = RendererConfiguration.getDefaultConf();
				}

				return PMS.get().getRootFolder(r).getDLNAResource(objectId, r);
			}
		} else {
			return Temp.add(uri, name, r);
		}
	}

	// Returns the uri if it's ours and exists or else the url of new Temp item (or null)
	public static String getValidResourceURL(String uri, String name, RendererConfiguration r) {
		if (isResourceUrl(uri)) {
			// Check existence
			return PMS.get().getGlobalRepo().exists(parseResourceId(uri)) ? uri : null; // TODO: attempt repair
		} else {
			DLNAResource d = Temp.add(uri, name, r);
			if (d != null) {
				return d.getURL("", true);
			}
		}
		return null;
	}

	public static class Rendering {
		RendererConfiguration r;
		Player p;
		DLNAMediaSubtitle s;
		String m;
		Rendering(DLNAResource d) {
			r = d.getDefaultRenderer();
			p = d.getPlayer();
			s = d.getMediaSubtitle();
			if (d.getMedia() != null) {
				m = d.getMedia().getMimeType();
			}
		}
	}

	public Rendering updateRendering(RendererConfiguration r) {
		Rendering rendering = new Rendering(this);
		Player p = resolvePlayer(r);
		LOGGER.debug("Switching rendering context to '{} [{}]' from '{} [{}]'", r, p, rendering.r, rendering.p);
		setDefaultRenderer(r);
		setPlayer(p);
		setPreferredMimeType(r);
		return rendering;
	}

	public void updateRendering(Rendering rendering) {
		LOGGER.debug("Switching rendering context to '{} [{}]' from '{} [{}]'", rendering.r, rendering.p, getDefaultRenderer(), getPlayer());
		setDefaultRenderer(rendering.r);
		setPlayer(rendering.p);
		media_subtitle = rendering.s;
		if (media != null) {
			media.setMimeType(rendering.m);
		}
	}

	public DLNAResource isCoded() {
		DLNAResource tmp = this;
		while (tmp != null) {
			if (tmp instanceof CodeEnter) {
				return tmp;
			}

			tmp = tmp.getParent();
		}

		return null;
	}

	public boolean isCodeValid(DLNAResource r) {
		DLNAResource res = r.isCoded();
		if (res != null) {
			if (res instanceof CodeEnter) {
				return ((CodeEnter) res).validCode(r);
			}
		}

		// normal case no code in path code is always valid
		return true;
	}

	public boolean quietPlay() {
		return false;
	}

	public long getStartTime() {
		return startTime;
	}

	private void addDynamicPls(final DLNAResource child) {
		final DLNAResource dynPls = PMS.get().getDynamicPls();
		if (dynPls == child || child.getParent() == dynPls) {
			return;
		}

		if (child instanceof VirtualVideoAction) {
			// ignore these
			return;
		}

		if (dynamicPls == null) {
			dynamicPls = new VirtualFolder(Messages.getString("PMS.147"), null);
			addChildInternal(dynamicPls);
			dynamicPls.addChild(dynPls);
		}

		if (dynamicPls != null) {
			String str = Messages.getString("PluginTab.9") + " " + child.getDisplayName() + " " + Messages.getString("PMS.148");
			VirtualVideoAction vva = new VirtualVideoAction(str, true) {
				@Override
				public boolean enable() {
					PMS.get().getDynamicPls().add(child);
					return true;
				}
			};
			vva.setParent(this);
			dynamicPls.addChildInternal(vva);
		}
	}

	public String getResolutionForKeepAR(int scaleWidth, int scaleHeight) {
		double videoAspectRatio = (double) scaleWidth / (double) scaleHeight;
		double rendererAspectRatio = 1.777777777777778;
		if (videoAspectRatio > rendererAspectRatio) {
			scaleHeight = (int) Math.round(scaleWidth / rendererAspectRatio);
		} else {
			scaleWidth  = (int) Math.round(scaleHeight * rendererAspectRatio);
		}

		scaleWidth  = Player.convertToModX(scaleWidth, 4);
		scaleHeight = Player.convertToModX(scaleHeight, 4);
		return scaleWidth + "x" + scaleHeight;
	}
}
