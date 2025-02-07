/*
 * Universal Media Server, for streaming any medias to DLNA
 * compatible renderers based on the http://www.ps3mediaserver.org.
 * Copyright (C) 2012  UMS developers.
 *
 * This program is a free software; you can redistribute it and/or
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

package net.pms.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.Collator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.*;
import net.pms.encoders.Player;
import net.pms.encoders.PlayerFactory;
import net.pms.external.ExternalFactory;
import net.pms.external.ExternalListener;
import net.pms.formats.Format;
import net.pms.formats.v2.SubtitleType;
import net.pms.PMS;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UMSUtils {
	private static final Collator collator;
	private static final Logger LOGGER = LoggerFactory.getLogger(UMSUtils.class);

	static {
		collator = Collator.getInstance();
		collator.setStrength(Collator.PRIMARY);
	}

	public static void postSearch(List<DLNAResource> files, String searchCriteria) {
		if (files == null || searchCriteria == null) {
			return;
		}
		searchCriteria = searchCriteria.toLowerCase();
		for (int i = files.size() - 1; i >= 0; i--) {
			DLNAResource res = files.get(i);

			if (res.isSearched()) {
				continue;
			}

			boolean keep = res.getName().toLowerCase().contains(searchCriteria);
			final DLNAMediaInfo media = res.getMedia();

			if (!keep && media != null && media.getAudioTracksList() != null) {
				for (int j = 0; j < media.getAudioTracksList().size(); j++) {
					DLNAMediaAudio audio = media.getAudioTracksList().get(j);
					if (audio.getAlbum() != null) {
						keep |= audio.getAlbum().toLowerCase().contains(searchCriteria);
					}
					if (audio.getArtist() != null) {
						keep |= audio.getArtist().toLowerCase().contains(searchCriteria);
					}
					if (audio.getSongname() != null) {
						keep |= audio.getSongname().toLowerCase().contains(searchCriteria);
					}
				}
			}

			if (!keep) { // dump it
				files.remove(i);
			}
		}
	}

	// Sort constants
	public static final int SORT_LOC_SENS =  0;
	public static final int SORT_MOD_NEW =   1;
	public static final int SORT_MOD_OLD =   2;
	public static final int SORT_INS_ASCII = 3;
	public static final int SORT_LOC_NAT =   4;
	public static final int SORT_RANDOM =    5;
	public static final int SORT_NO_SORT =   6;

	public static void sort(List<File> files, int method) {
		switch (method) {
			case SORT_NO_SORT: // no sorting
				break;
			case SORT_LOC_NAT: // Locale-sensitive natural sort
				Collections.sort(files, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						String filename1ToSort = FileUtil.renameForSorting(f1.getName());
						String filename2ToSort = FileUtil.renameForSorting(f2.getName());

						return NaturalComparator.compareNatural(collator, filename1ToSort, filename2ToSort);
					}
				});
				break;
			case SORT_INS_ASCII: // Case-insensitive ASCIIbetical sort
				Collections.sort(files, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						String filename1ToSort = FileUtil.renameForSorting(f1.getName());
						String filename2ToSort = FileUtil.renameForSorting(f2.getName());

						return filename1ToSort.compareToIgnoreCase(filename2ToSort);
					}
				});
				break;
			case SORT_MOD_OLD: // Sort by modified date, oldest first
				Collections.sort(files, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
					}
				});
				break;
			case SORT_MOD_NEW: // Sort by modified date, newest first
				Collections.sort(files, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
					}
				});
				break;
			case SORT_RANDOM: // Random
				Collections.shuffle(files, new Random(System.currentTimeMillis()));
				break;
			case SORT_LOC_SENS: // Same as default
			default: // Locale-sensitive A-Z
				Collections.sort(files, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						String filename1ToSort = FileUtil.renameForSorting(f1.getName());
						String filename2ToSort = FileUtil.renameForSorting(f2.getName());

						return collator.compare(filename1ToSort, filename2ToSort);
					}
				});
				break;
		}
	}

	public static String logFormat(String msg) {
		DateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm:ss");
		Date date = new Date();

		String[] messageDisplay = msg.replaceFirst("]", "string that should never match").split("string that should never match");
		return dateFormat.format(date) + " " + messageDisplay[1];
	}

	private static int getHW(String[] s, int pos) {
		if (pos > s.length - 1) {
			return 0;
		}
		try {
			return Integer.parseInt(s[pos].trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@SuppressWarnings("deprecation")
	public static InputStream scaleThumb(InputStream in, RendererConfiguration r) throws IOException {
		if (in == null) {
			return in;
		}
		String ts = r.getThumbSize();
		if (StringUtils.isEmpty(ts) && StringUtils.isEmpty(r.getThumbBG())) {
			// no need to convert here
			return in;
		}
		int w;
		int h;
		Color col = null;
		BufferedImage img;
		try {
			img = ImageIO.read(in);
		} catch (Exception e) {
			// catch whatever is thrown at us
			// we can at least log it
			LOGGER.debug("couldn't read thumb to manipulate it " + e);
			img = null; // to make sure
		}
		if (img == null) {
			return in;
		}
		w = img.getWidth();
		h = img.getHeight();
		if (StringUtils.isNotEmpty(ts)) {
			// size limit thumbnail
			w = getHW(ts.split("x"), 0);
			h = getHW(ts.split("x"), 1);
			if (w == 0 || h == 0) {
				LOGGER.debug("bad thumb size {} skip scaling", ts);
				w = h = 0; // just to make sure
			}
		}
		if (StringUtils.isNotEmpty(r.getThumbBG())) {
			try {
				Field field = Color.class.getField(r.getThumbBG());
				col = (Color) field.get(null);
			} catch (Exception e) {
				LOGGER.debug("bad color name " + r.getThumbBG());
			}
		}
		if (w == 0 && h == 0 && col == null) {
			return in;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BufferedImage img1 = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img1.createGraphics();
		if (col != null) {
			g.setColor(col);
		}
		g.fillRect(0, 0, w, h);
		g.drawImage(img, 0, 0, w, h, null);
		ImageIO.write(img1, "jpeg", out);
		out.flush();
		return new ByteArrayInputStream(out.toByteArray());
	}

	private static String fixTimeStr(String str) {
		if (str.equals("NOT_IMPLEMENTED")) {
			return " ";
		}
		if(str.charAt(0) == ':')   {
			// remove stray ':' at the start
			str = str.substring(1);
		}
		int pos = str.indexOf(".");
		if (pos != -1) {
			// remove millisecond portion
			str = str.substring(0, pos);
		}
		return str;
	}

	public static String playedDurationStr(String current, String duration) {
		String pos = fixTimeStr(StringUtil.shortTime(current, 4));
		String dur = fixTimeStr(StringUtil.shortTime(duration, 4));
		return pos + (pos.equals("0:00") ? "" : dur.equals("0:00") ? "" : (" / " + dur));
	}

	private static String iso639(String s) {
		String[] tmp = s.split(",");
		StringBuilder res = new StringBuilder();
		String sep = "";
		for (String tmp1 : tmp) {
			res.append(sep).append(Iso639.getISO639_2Code(tmp1));
			sep = ",";
		}
		if (StringUtils.isNotEmpty(res)) {
			return res.toString();
		}
		return s;
	}

	public static String getLangList(RendererConfiguration r) {
		return getLangList(r, false);
	}

	public static String getLangList(RendererConfiguration r, boolean three) {
		String res;
		if (r != null) {
			res = r.getSubLanguage();
		}
		else {
			res = PMS.getConfiguration().getSubtitlesLanguages();
		}
		if (three) {
			res = iso639(res);
		}
		return res;
	}

	/**
	 * Bitwise constants relating to playlist management.
	 */
	public interface IOListModes {
		public static final int PERMANENT = 1;
		public static final int AUTOSAVE = 2;
		public static final int AUTOREMOVE = 4;
	}

	/**
	 * A DLNAResource list with built-in file i/o.
	 */
	public static class IOList extends ArrayList<DLNAResource> implements IOListModes {
		private static final long serialVersionUID = 8042924548275374060L;
		private File file;
		private int mode;

		public IOList(String uri, int mode) {
			this.mode = mode;
			if (! StringUtils.isBlank(uri)) {
				file = new File(uri);
				load(file);
			} else {
				file = null;
			}
		}

		@Override
		public boolean add(DLNAResource d) {
			super.add(d);
			if (isMode(AUTOSAVE)) {
				save();
			}
			return true;
		}

		@Override
		public DLNAResource remove(int index) {
			DLNAResource d = super.remove(index);
			if (isMode(AUTOSAVE)) {
				save();
			}
			return d;
		}

		public boolean isMode(int m) {
			return (mode & m) == m;
		}

		public File getFile()  {
			return file;
		}

		public void load(File f) {
			if (f.exists()) {
				file = f;
				clear();
				try {
					read(this, f);
				} catch (IOException e) {
					LOGGER.debug("Error loading resource list '{}': {}", f, e);
				}
				if (PMS.getConfiguration().getSortMethod(f) == UMSUtils.SORT_RANDOM) {
					Collections.shuffle(this);
				}
			}
		}

		public void save() {
			if (file != null) {
				save(file);
			}
		}

		public void save(File f) {
			if (size() > 0) {
				try {
					write(this, f);
				} catch (IOException e) {
					LOGGER.debug("Error saving resource list to '{}': {}", f, e);
				}
			} else if (f != null && f.exists()) {
				// Save = delete for empty playlists
				f.delete();
			}
		}

		public static void write(List<DLNAResource> playlist, File f) throws IOException {
			Date now = new Date();
			try (FileWriter out = new FileWriter(f)) {
				StringBuilder sb = new StringBuilder();
				sb.append("######\n");
				sb.append("## __UPS__\n");
				sb.append("## NOTE!!!!!\n");
				sb.append("## This file is auto generated\n");
				sb.append("## Edit with EXTREME care\n");
				sb.append("## Generated: ");
				sb.append(now.toString());
				sb.append("\n");
				for (DLNAResource r : playlist) {
					String data = r.write();
					if (!org.apache.commons.lang.StringUtils.isEmpty(data) && sb.indexOf(data) == -1) {
						ExternalListener external = r.getMasterParent();
						String id;
						if (external != null) {
							id = external.getClass().getName();
						} else {
							id = "internal:" + r.getClass().getName();
						}

						sb.append("master:").append(id).append(";");
						if (r.getPlayer() != null) {
							sb.append("player:").append(r.getPlayer().toString()).append(";");
						}
						if (r.isResume()) {
							sb.append("resume");
							sb.append(r.getResume().getResumeFile().getAbsolutePath());
							sb.append(";");
						}
						if (r.getMediaSubtitle() != null) {
							DLNAMediaSubtitle sub = r.getMediaSubtitle();
							if (sub.getLang() != null
									&& sub.getId() != -1) {
								sb.append("sub");
								sb.append(sub.getLang());
								sb.append(",");
								if (sub.isExternal()) {
									sb.append("file:");
									sb.append(sub.getExternalFile().getAbsolutePath());
								} else {
									sb.append("id:");
									sb.append("").append(sub.getId());
								}
								sb.append(";");
							}
						}
						sb.append(data);
						sb.append("\n");
					}
				}
				out.write(sb.toString());
				out.flush();
			}
		}

		private static ExternalListener findMasterParent(String className) {
			for (ExternalListener l : ExternalFactory.getExternalListeners()) {
				if (className.equals(l.getClass().getName())) {
					return l;
				}
			}
			return null;
		}

		private static Player findPlayer(String playerName) {
			for (Player p : PlayerFactory.getPlayers()) {
				if (playerName.equals(p.name())) {
					return p;
				}
			}
			return null;
		}

		private static DLNAResource parse(String clazz, String data) {
			boolean error = false;
			if (clazz.contains("RealFile")) {
				if (data.contains(">")) {
					String[] tmp = data.split(">");
					return new RealFile(new File(tmp[1]), tmp[0]);
				}
				error = true;
			}
			if (clazz.contains("SevenZipEntry")) {
				if (data.contains(">")) {
					String[] tmp = data.split(">");
					long len = Long.parseLong(tmp[2]);
					return new SevenZipEntry(new File(tmp[1]), tmp[0], len);
				}
				error = true;
			}
			if (clazz.contains("ZippedEntry")) {
				if (data.contains(">")) {
					String[] tmp = data.split(">");
					long len = Long.parseLong(tmp[2]);
					return new ZippedEntry(new File(tmp[1]), tmp[0], len);
				}
				error = true;
			}
			if (clazz.contains("WebStream")) {
				if (data.contains(">")) {
					String[] tmp = data.split(">");
					int type;
					try {
						type = Integer.parseInt(tmp[3]);
					} catch (NumberFormatException e) {
						type = Format.UNKNOWN;
					}
					return new WebStream(tmp[0], tmp[1], tmp[2], type);
				}
				error = true;
			}

			if (error) {
				LOGGER.debug("Error parsing playlist resource:");
				LOGGER.debug("clazz: " + clazz);
				LOGGER.debug("data:" + data);
			}

			return null;
		}

		public static void read(List<DLNAResource> playlist, File f) throws IOException {
			if (!f.exists()) {
				return;
			}
			try (BufferedReader in = new BufferedReader(new FileReader(f))) {
				String str;

				while ((str = in.readLine()) != null) {
					if (org.apache.commons.lang.StringUtils.isEmpty(str)) {
						continue;
					}
					if (str.startsWith("#")) {
						continue;
					}
					str = str.trim();
					if (!str.startsWith("master:")) {
						continue;
					}
					str = str.substring(7);
					int pos = str.indexOf(';');
					if (pos == -1) {
						continue;
					}
					String master = str.substring(0, pos);
					str = str.substring(pos + 1);
					pos = str.indexOf(';');
					String subData = null;
					String resData = null;
					DLNAResource res = null;
					Player player = null;
					while (pos != -1) {
						if (str.startsWith("player:")) {
							// find last player
							player = findPlayer(str.substring(7, pos));
						}
						if (str.startsWith("resume")) {
							// resume data
							resData = str.substring(6, pos);
						}
						if (str.startsWith("sub")) {
							// subs data
							subData = str.substring(3, pos);
						}
						str = str.substring(pos + 1);
						pos = str.indexOf(';');
					}
					LOGGER.debug("master is " + master + " str " + str);
					ExternalListener external;
					if (master.startsWith("internal:")) {
						res = parse(master.substring(9), str);
					} else {
						external = findMasterParent(master);
						if (external != null) {
							res = resolveCreateMethod(external, str);
							if (res != null) {
								LOGGER.debug("set masterparent for " + res + " to " + external);
								res.setMasterParent(external);
							}
						}
					}
					if (res != null) {
						if (resData != null) {
							ResumeObj r = new ResumeObj(new File(resData));
							if (!r.isDone()) {
								r.read();
								res.setResume(r);
							}
						}
						res.setPlayer(player);
						if (subData != null) {
							DLNAMediaSubtitle s = res.getMediaSubtitle();
							if (s == null) {
								s = new DLNAMediaSubtitle();
								res.setMediaSubtitle(s);
							}
							String[] tmp = subData.split(",");
							s.setLang(tmp[0]);
							subData = tmp[1];
							if (subData.startsWith("file:")) {
								String sFile = subData.substring(5);
								s.setExternalFile(new File(sFile));
								s.setId(100);
								SubtitleType t = SubtitleType.valueOfFileExtension(FileUtil.getExtension(sFile));
								s.setType(t);
							} else if (subData.startsWith("id:")) {
								s.setId(Integer.parseInt(subData.substring(3)));
							}
						}
						playlist.add(res);
					}
				}
			}
		}

		public static DLNAResource resolveCreateMethod(ExternalListener l, String arg) {
			// FIXME: this effectively imposes an undeclared interface, better to declare it explicitly
			Method create;
			try {
				Class<?> clazz = l.getClass();
				create = clazz.getDeclaredMethod("create", String.class);
				return (DLNAResource) create.invoke(l, arg);
				// Ignore all errors
			} catch (SecurityException | NoSuchMethodException | IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
				LOGGER.debug("Unable to recreate {} item: {}", l.name(), arg);
			}
			return null;
		}
	}
}
