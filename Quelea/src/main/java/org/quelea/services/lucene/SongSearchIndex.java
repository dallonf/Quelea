/*
 * This file is part of Quelea, free projection software for churches.
 * 
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
package org.quelea.services.lucene;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilterFactory;
import org.apache.lucene.analysis.standard.StandardTokenizerFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.complexPhrase.ComplexPhraseQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.ThreadInterruptedException;
import org.quelea.data.displayable.SongDisplayable;
import org.quelea.services.utils.LoggerUtils;

/**
 * The search index of songs.
 *
 * @author Michael
 */
public class SongSearchIndex implements SearchIndex<SongDisplayable> {

    private static final Logger LOGGER = LoggerUtils.getLogger();
    private final Analyzer analyzer;
    private final Directory index;
    private final Map<Long, SongDisplayable> songs;

    /**
     * Create a new empty search index.
     */
    public SongSearchIndex() {
        songs = new HashMap<>();
        try {
            analyzer = CustomAnalyzer.builder()
                    .withTokenizer(StandardTokenizerFactory.class)
                    .addTokenFilter(LowerCaseFilterFactory.class)
                    .addTokenFilter(ASCIIFoldingFilterFactory.class)
                    .build();
            index = new MMapDirectory(Files.createTempDirectory("quelea-mmap-song").toAbsolutePath());
        }
        catch(IOException ex) {
            LOGGER.log(Level.SEVERE, "Couldn't create song search index");
            throw new RuntimeException("Couldn't create song search index", ex);
        }
    }

    @Override
    public int size() {
        return songs.size();
    }

    /**
     * Add a song to the index.
     *
     * @param song the song to add.
     */
    @Override
    public void add(SongDisplayable song) {
        List<SongDisplayable> songList = new ArrayList<>();
        songList.add(song);
        addAll(songList);
    }

    /**
     * Add a number of songs to the index. This is much more efficient than
     * calling add() repeatedly because it just uses one writer rather than
     * opening and closing one for each individual operation.
     *
     * @param songList the song list to add.
     */
    @Override
    public synchronized void addAll(Collection<? extends SongDisplayable> songList) {
        Pattern p = Pattern.compile("[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            for (SongDisplayable song : songList) {
                Document doc = new Document();
                if (song.getTitle() != null) {
                    doc.add(new TextField("title", p.matcher(song.getTitle()).replaceAll(""), Field.Store.NO));
                }
                if (song.getAuthor() != null) {
                    doc.add(new TextField("author", p.matcher(song.getAuthor()).replaceAll(""), Field.Store.NO));
                }
                if (song.getLyrics(false, false, false) != null) {
                    doc.add(new TextField("lyrics", p.matcher(song.getLyrics(false, false, false)).replaceAll(""), Field.Store.NO));
                }
                doc.add(new TextField("number", p.matcher(Long.toString(song.getID())).replaceAll(""), Field.Store.YES));
                writer.addDocument(doc);
                songs.put(song.getID(), song);
                LOGGER.log(Level.FINE, "Added song to index: {0}", song.getTitle());
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Couldn't add value to index", ex);
        }
    }

    /**
     * Remove the given song from the index.
     *
     * @param song the song to remove.
     */
    @Override
    public synchronized void remove(SongDisplayable song) {
        try (IndexWriter writer = new IndexWriter(index, new IndexWriterConfig(analyzer))) {
            writer.deleteDocuments(new Term("number", Long.toString(song.getID())));
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Couldn't remove value from index", ex);
        }
    }

    /**
     * Update the given song in the index.
     *
     * @param song the song to update.
     */
    @Override
    public void update(SongDisplayable song) {
        remove(song);
        add(song);
    }

    /**
     * Get a song by its ID.
     *
     * @param id the id of the song.
     * @return the song with the given id.
     */
    public synchronized SongDisplayable getByID(long id) {
        return songs.get(id);
    }

    /**
     * Search for songs that match the given filter.
     *
     * @param queryString the query to use to search.
     * @param type TITLE or BODY, depending on what to search in. BODY is
     * equivalent to the lyrics, TITLE the title.
     * @return an array of songs that match the filter.
     */
    @Override
    public synchronized SongDisplayable[] filter(String queryString, FilterType type) {
        String sanctifyQueryString = SearchIndexUtils.makeLuceneQuery(queryString);
        if (songs.isEmpty() || sanctifyQueryString.trim().isEmpty()) {
            return songs.values().toArray(new SongDisplayable[songs.size()]);
        }
        String typeStr;
        if (type == FilterType.BODY) {
            typeStr = "lyrics";
        } else if (type == FilterType.TITLE) {
            typeStr = "title";
        } else if (type == FilterType.AUTHOR) {
            typeStr = "author";
        } else {
            LOGGER.log(Level.SEVERE, "Unknown type: {0}", type);
            return new SongDisplayable[0];
        }
        List<SongDisplayable> ret;
        try (DirectoryReader dr = DirectoryReader.open(index)) {
            IndexSearcher searcher = new IndexSearcher(dr);
            Query q = new ComplexPhraseQueryParser(typeStr, analyzer).parse(sanctifyQueryString);
            TopScoreDocCollector collector = TopScoreDocCollector.create(1000,10000);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            ret = new ArrayList<>();
            for (int i = 0; i < hits.length; ++i) {
                int docId = hits[i].doc;
                Document d = searcher.doc(docId);
                final Long songNumber = Long.parseLong(d.get("number"));
                SongDisplayable song = songs.get(songNumber);
                ret.add(song);
            }
            if (type == FilterType.BODY) {
                for (SongDisplayable song : filter(queryString, FilterType.TITLE)) {
                    ret.remove(song);
                }
            }
            return ret.toArray(new SongDisplayable[ret.size()]);
        }
        catch(ClosedByInterruptException|ThreadInterruptedException ex) {
            //Ignore, thread is being shut down by other character being typed
            return new SongDisplayable[0];
        }
        catch (ParseException | IOException ex) {
            LOGGER.log(Level.WARNING, "Invalid query string: " + sanctifyQueryString, ex);
            return new SongDisplayable[0];
        }
    }

    /**
     * Remove everything from this index.
     */
    @Override
    public synchronized void clear() {
        SearchIndexUtils.clearIndex(index);
    }
}
