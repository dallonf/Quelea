/* 
 * This file is part of Quelea, free projection software for churches.
 * 
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.quelea.services.importexport;

import com.github.berry120.jopenlyrics.OpenLyricsException;
import com.github.berry120.jopenlyrics.OpenLyricsObject;
import com.github.berry120.jopenlyrics.Verse;
import com.github.berry120.jopenlyrics.VerseLine;
import com.github.berry120.jopenlyrics.properties.TitleProperty;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.ParserConfigurationException;
import org.quelea.data.displayable.SongDisplayable;
import org.quelea.services.utils.LoggerUtils;
import org.quelea.windows.main.StatusPanel;
import org.xml.sax.SAXException;

/**
 * A parser for parsing zip files of openlyrics.
 *
 * @author Michael
 */
public class OpenLyricsParser implements SongParser {

    private static final Logger LOGGER = LoggerUtils.getLogger();

    /**
     * Get a list of the openlyrics songs contained in the given zip file.
     *
     * @param location the location of the zip file.
     * @return a list of the songs found.
     * @throws IOException if something goes wrong.
     */
    @Override
    public List<SongDisplayable> getSongs(File location, StatusPanel statusPanel) throws IOException {
        final ZipFile file = new ZipFile(location, Charset.forName("Cp437"));
        List<SongDisplayable> ret = new ArrayList<>();
        try {
            final Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                
                OpenLyricsObject ol = new OpenLyricsObject(file.getInputStream(entry));
                String lyrics = getLyrics(ol);
                String title = getTitle(ol.getProperties().getTitleProperty());
                String comments = getComments(ol.getProperties().getComments());
                String ccli = ol.getProperties().getCcliNo();
                String copyright = ol.getProperties().getCopyright();
                String author = getAuthor(ol);
                if (!lyrics.isEmpty()) {
                    SongDisplayable displayable = new SongDisplayable(title, author);
                    displayable.setLyrics(lyrics);
                    displayable.setInfo(comments);
                    displayable.setCopyright(copyright);
                    displayable.setCcli(ccli);
                    ret.add(displayable);
                } else {
                    LOGGER.log(Level.INFO, "Song had empty lyrics");
                }
            }
        } catch (OpenLyricsException | IOException | ParserConfigurationException | SAXException ex) {
            LOGGER.log(Level.WARNING, "Error importing openlyrics archive", ex);
        } finally {
            file.close();
        }
        return ret;
    }

    private String getComments(List<String> comments) {
        StringBuilder ret = new StringBuilder();
        if (comments != null) {
            for (String comment : comments) {
                ret.append(comment).append('\n');
            }
        }
        return ret.toString().trim();
    }

    private String getTitle(TitleProperty titleProp) {
        StringBuilder ret = new StringBuilder();
        List<String> titles = new ArrayList<>();
        for (Locale locale : titleProp.getTitleLocales()) {
            titles.add(titleProp.getTitle(locale));
        }
        for (int i = 0; i < titles.size(); i++) {
            String title = titles.get(i);
            if (title.matches("[0-9]+\\.")) { //Deal with "number" titles
                title = title.substring(0, title.length() - 1);
                while (title.length() < 4) {
                    title = "0" + title;
                }
            }
            ret.append(title);
            if (i < titles.size() - 1) {
                ret.append(" - ");
            }
        }
        return ret.toString();
    }

    private String getAuthor(OpenLyricsObject ol) {
        StringBuilder ret = new StringBuilder();
        List<String> authors = ol.getProperties().getAuthors();
        for (int i = 0; i < authors.size(); i++) {
            ret.append(authors.get(i));
            if (i < authors.size() - 1) {
                ret.append(", ");
            }
        }
        return ret.toString().trim();
    }

    /**
     * Get lyrics as a string from an openlyrics object.
     *
     * @param ol the openlyrics POJO
     * @return the lyrics as a string
     */
    private String getLyrics(OpenLyricsObject ol) {
        StringBuilder ret = new StringBuilder();
        if (ol == null || ol.getVerses() == null) {
            LOGGER.log(Level.WARNING, "Couldn't create openlyrics object");
        } else {
            for (Verse verse : ol.getVerses()) {
                if (!verse.getName().isEmpty()) {
                    ret.append(parseVerseName(verse.getName()));
                }
                for (VerseLine line : verse.getLines()) {
                    ret.append(line.getText().trim()).append('\n');
                }
                ret.append('\n');
            }
        }
        return ret.toString().trim();
    }

    /**
     * Parse verse name as a section title string.
     *
     * @param verseName the openlyrics verse name
     * @return the section title as a string, or an empty string if the name could not be parsed
     */
    private String parseVerseName(String verseName)
    {
        String ret = "";
        if (verseName.toLowerCase().startsWith("v")) {
            // This is a verse, e.g. "v1".
            if (verseName.length() > 1) {
                ret = "Verse " + verseName.substring(1) + "\n";
            } else {
                ret = "Verse\n";
            }
        } else if (verseName.toLowerCase().startsWith("c")) {
            // This is a chorus, e.g. "c1".
            if (verseName.length() > 1) {
                ret = "Chorus " + verseName.substring(1) + "\n";
            } else {
                ret = "Chorus\n";
            }
        } else if (verseName.toLowerCase().startsWith("b")) {
            // This is a bridge, e.g. "b1".
            if (verseName.length() > 1) {
                ret = "Bridge " + verseName.substring(1) + "\n";
            } else {
                ret = "Bridge\n";
            }
        } else if (verseName.toLowerCase().startsWith("p")) {
            // This is a pre-chorus, e.g. "p1".
            if (verseName.length() > 1) {
                ret = "Pre-chorus " + verseName.substring(1) + "\n";
            } else {
                ret = "Pre-chorus\n";
            }
        } else if (verseName.toLowerCase().startsWith("i")) {
            // This is an intro, e.g. "i1".
            if (verseName.length() > 1) {
                ret = "Intro " + verseName.substring(1) + "\n";
            } else {
                ret = "Intro\n";
            }
        } else if (verseName.toLowerCase().startsWith("e")) {
            // This is an ending, e.g. "e1".
            if (verseName.length() > 1) {
                ret = "Ending " + verseName.substring(1) + "\n";
            } else {
                ret = "Ending\n";
            }
        }
        return ret;
    }
}
