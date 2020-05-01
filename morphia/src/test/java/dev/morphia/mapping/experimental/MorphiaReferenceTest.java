package dev.morphia.mapping.experimental;

import dev.morphia.TestBase;
import dev.morphia.aggregation.experimental.stages.Lookup;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Reference;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.morphia.aggregation.experimental.stages.Unwind.on;
import static dev.morphia.query.experimental.filters.Filters.eq;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Category(Reference.class)
public class MorphiaReferenceTest extends TestBase {
    @Test
    public void basicReference() {
        final Author author = new Author("Jane Austen");
        getDs().save(author);

        final Book book = addBook(author);

        final Book loaded = getDs().find(Book.class).filter(eq("_id", book.id)).first();
        Assert.assertFalse(loaded.author.isResolved());
        Assert.assertEquals(author, loaded.author.get());
        assertTrue(loaded.author.isResolved());
    }

    @Test
    public void listReference() {
        final Author author = new Author("Jane Austen");
        getDs().save(author);

        List<Book> list = addListOfBooks(author);
        getDs().save(author);

        final Author loaded = getDs().find(Author.class).filter(eq("_id", author.getId())).first();
        validateList(list, loaded);
    }

    @Test
    public void mapReference() {
        final Author author = new Author("Jane Austen");
        getDs().save(author);

        Map<String, Book> books = addBookMap(author);
        getDs().save(author);

        final Author loaded = getDs().find(Author.class).filter(eq("_id", author.getId())).first();
        validateMap(books, loaded);
    }

    @Test
    public void setReference() {
        final Author author = new Author("Jane Austen");
        getDs().save(author);

        Set<Book> set = addSetOfBooks(author);
        getDs().save(author);

        final Author loaded = getDs().find(Author.class).filter(eq("_id", author.getId())).first();
        validateSet(set, loaded);
    }

    @Test
    public void testAggregationLookups() {
        final Author author = new Author("Jane Austen");
        getDs().save(author);

        Book book = addBook(author);
        Set<Book> set = addSetOfBooks(author);
        List<Book> list = addListOfBooks(author);
        //        Map<String, Book> map = addBookMap(author);

        getDs().save(author);
        Object document = getDs().aggregate(Author.class)
                                 .lookup(Lookup.from(Book.class)
                                               .as("set")
                                               .foreignField("_id")
                                               .localField("set"))
                                 .lookup(Lookup.from(Book.class)
                                               .as("list")
                                               .foreignField("_id")
                                               .localField("list"))
                                 //  TODO how to fetch the values from a nested document for cross-referencing?
                                 //                                   .lookup(Lookup.from(Book.class)
                                 //                                                 .as("map")
                                 //                                                 .foreignField("_id")
                                 //                                                 .localField("map.$"))
                                 .execute(Author.class)
                                 .tryNext();

        final Author loaded = (Author) document;
        Book foundBook = getDs().aggregate(Book.class)
                                .lookup(Lookup.from(Author.class)
                                              .as("author")
                                              .foreignField("_id")
                                              .localField("author"))
                                .unwind(on("author"))
                                .execute(Book.class)
                                .next();
        Assert.assertTrue(foundBook.author.isResolved());
        Assert.assertEquals(author, foundBook.author.get());

        Assert.assertTrue(loaded.set.isResolved());
        final Set<Book> set1 = loaded.getSet();
        assertEquals(set.size(), set1.size());
        for (final Book book1 : set) {
            assertTrue("Looking for " + book1 + " in " + set1, set1.contains(book1));
        }

        Assert.assertTrue(loaded.list.isResolved());
        Assert.assertEquals(list, loaded.getList());
        for (final Book book1 : list) {
            assertTrue("Looking for " + book1 + " in " + list, list.contains(book1));
        }
        assertTrue(loaded.list.isResolved());
        //        validateMap(map, loaded);
    }

    protected Book addBook(final Author author) {
        final Book book = new Book("Pride and Prejudice");
        book.setAuthor(author);
        return getDs().save(book);
    }

    protected Map<String, Book> addBookMap(final Author author) {
        Map<String, Book> books = new LinkedHashMap<>();
        for (final Book book : new Book[]{
            new Book("Sense and Sensibility"),
            new Book("Pride and Prejudice"),
            new Book("Mansfield Park"),
            new Book("Emma"),
            new Book("Northanger Abbey")}) {
            book.setAuthor(author);
            getDs().save(book);
            books.put(book.name, book);
        }
        author.setMap(books);
        return books;
    }

    protected List<Book> addListOfBooks(final Author author) {
        List<Book> list = new ArrayList<>();
        list.add(new Book("Sense and Sensibility"));
        list.add(new Book("Pride and Prejudice"));
        list.add(new Book("Mansfield Park"));
        list.add(new Book("Emma"));
        list.add(new Book("Northanger Abbey"));
        for (final Book book : list) {
            book.setAuthor(author);
            getDs().save(book);
        }
        author.setList(list);
        return list;
    }

    protected Set<Book> addSetOfBooks(final Author author) {
        Set<Book> set = new HashSet<>(5);
        set.add(new Book("Sense and Sensibility"));
        set.add(new Book("Pride and Prejudice"));
        set.add(new Book("Mansfield Park"));
        set.add(new Book("Emma"));
        set.add(new Book("Northanger Abbey"));
        for (final Book book : set) {
            book.setAuthor(author);
            getDs().save(book);
        }
        author.setSet(set);
        return set;
    }

    protected void validateList(final List<Book> list, final Author loaded) {
        Assert.assertFalse(loaded.list.isResolved());
        Assert.assertEquals(list, loaded.getList());
        assertTrue(loaded.list.isResolved());
    }

    protected void validateMap(final Map<String, Book> books, final Author loaded) {
        Assert.assertFalse(loaded.map.isResolved());
        Assert.assertEquals(books, loaded.getMap());
        assertTrue(loaded.map.isResolved());
    }

    protected void validateSet(final Set<Book> set, final Author loaded) {
        Assert.assertTrue(loaded.set.isResolved());
        final Set<Book> set1 = loaded.getSet();

        assertEquals(set.size(), set1.size());

        for (final Book book : set) {
            assertTrue("Looking for " + book + " in " + set1, set1.contains(book));
        }

        assertTrue(loaded.set.isResolved());
    }

    @Entity
    private static class Author {
        @Id
        private ObjectId id;

        private String name;

        private MorphiaReference<List<Book>> list;
        private MorphiaReference<Set<Book>> set;
        private MorphiaReference<Map<String, Book>> map;

        public Author() {
        }

        public Author(final String name) {
            this.name = name;
        }

        public ObjectId getId() {
            return id;
        }

        public void setId(final ObjectId id) {
            this.id = id;
        }

        public List<Book> getList() {
            return list.get();
        }

        public void setList(final List<Book> list) {
            this.list = MorphiaReference.wrap(list);
        }

        public Map<String, Book> getMap() {
            return map.get();
        }

        public void setMap(final Map<String, Book> map) {
            this.map = MorphiaReference.wrap(map);
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public Set<Book> getSet() {
            return set.get();
        }

        public void setSet(final Set<Book> set) {
            this.set = MorphiaReference.wrap(set);
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Author author = (Author) o;

            if (id != null ? !id.equals(author.id) : author.id != null) {
                return false;
            }
            return name != null ? name.equals(author.name) : author.name == null;
        }
    }

    @Entity
    private static class Book {
        @Id
        private ObjectId id;
        private String name;
        private MorphiaReference<Author> author;

        public Book() {
        }

        public Book(final String name) {
            this.name = name;
        }

        public Author getAuthor() {
            return author.get();
        }

        public void setAuthor(final Author author) {
            this.author = MorphiaReference.wrap(author);
        }

        public ObjectId getId() {
            return id;
        }

        public void setId(final ObjectId id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Book book = (Book) o;

            if (id != null ? !id.equals(book.id) : book.id != null) {
                return false;
            }
            return name != null ? name.equals(book.name) : book.name == null;
        }

        @Override
        public String toString() {
            return "Book{" +
                   "name='" + name + "', " +
                   "hash=" + hashCode() +
                   '}';
        }
    }
}
