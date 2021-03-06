package r.demo.graphql.core;

import graphql.schema.DataFetcher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import r.demo.graphql.annotation.Gql;
import r.demo.graphql.annotation.GqlDataFetcher;
import r.demo.graphql.annotation.GqlType;
import r.demo.graphql.domain.category.Category;
import r.demo.graphql.domain.category.CategoryRepo;
import r.demo.graphql.domain.content.Content;
import r.demo.graphql.domain.content.ContentRepo;
import r.demo.graphql.domain.sentence.Sentence;
import r.demo.graphql.domain.sentence.SentenceRepo;
import r.demo.graphql.domain.user.UserInfo;
import r.demo.graphql.domain.user.UserInfoRepo;
import r.demo.graphql.domain.word.Word;
import r.demo.graphql.domain.word.WordRepo;
import r.demo.graphql.response.DefaultResponse;
import r.demo.graphql.types.Paragraph;

import java.util.*;
import java.util.stream.Collectors;

@Gql
@Service
public class ContentDataFetcher {
    private final UserInfoRepo userRepo;
    private final ContentRepo contentRepo;
    private final WordRepo wordRepo;
    private final SentenceRepo sentenceRepo;
    private final CategoryRepo categoryRepo;

    public ContentDataFetcher(UserInfoRepo userRepo, ContentRepo contentRepo,
                              WordRepo wordRepo, SentenceRepo sentenceRepo, CategoryRepo categoryRepo) {
        this.userRepo = userRepo;
        this.contentRepo = contentRepo;
        this.wordRepo = wordRepo;
        this.sentenceRepo = sentenceRepo;
        this.categoryRepo = categoryRepo;
    }

    @GqlDataFetcher(type = GqlType.MUTATION)
    @SuppressWarnings("unchecked")
    public DataFetcher<?> createContent() {
        return environment -> {
            try {
                LinkedHashMap<String, Object> inputObj = environment.getArgument("input");
                List<LinkedHashMap<String, String>> words = (List<LinkedHashMap<String, String>>) inputObj.get("words"),
                        sentences = (List<LinkedHashMap<String, String>>) inputObj.get("sentences");
                List<Integer> categoryKeys = (List<Integer>) inputObj.get("categories");
                String title = inputObj.get("title").toString(),
                        ref = inputObj.get("ref").toString(),
                        captions = inputObj.get("captions").toString();

                UserInfo registerer = userRepo.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName())
                        .orElseThrow(() -> new IndexOutOfBoundsException("Invalid user"));
                Set<Category> categories = new HashSet<>();
                for (int categoryKey : categoryKeys) {
                    try {
                        categories.add(categoryRepo.findById((long) categoryKey).orElseThrow(IndexOutOfBoundsException::new));
                    } catch (IndexOutOfBoundsException ignored) { }
                }
                Content content = contentRepo.save(Content.builder().title(title).ref(ref).captions(captions).categories(categories).user(registerer).build());
                for (int i = 0; i < words.size(); i++) {
                    Paragraph word = new Paragraph(words.get(i));
                    wordRepo.save(Word.builder().content(content).eng(word.getEng()).kor(word.getKor()).sequence(i).build());
                }
                for (int i = 0; i < sentences.size(); i++) {
                    Paragraph sentence = new Paragraph(sentences.get(i));
                    sentenceRepo.save(Sentence.builder().content(content).eng(sentence.getEng()).kor(sentence.getKor()).sequence(i).build());
                }

                return new DefaultResponse(200);
            } catch (RuntimeException e) {
                return new DefaultResponse(HttpStatus.NOT_FOUND.value(), e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new DefaultResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
            }
        };
    }

    @GqlDataFetcher(type = GqlType.QUERY)
    public DataFetcher<?> allContents() {
        return environment -> {
            LinkedHashMap<String, Object> lhm = new LinkedHashMap<>();
            long categoryKey = Long.parseLong(environment.getArgument("category").toString());
            Integer option = environment.getArgument("option");
            final LinkedHashMap<String, Object> req = environment.getArgument("pr");
            int page = Integer.parseInt(req.get("page").toString()),
                    renderItem = Integer.parseInt(req.get("renderItem").toString());

            try {
                Category category;
                Page<Content> contents;
                if (categoryKey != -1) {
                    category = categoryRepo.findById(categoryKey).orElseThrow(IllegalArgumentException::new);
                    if (option != null && option == 1) {
                        Set<Long> filters = contentRepo.findAllByCategory(category).stream().map(Content::getId).collect(Collectors.toSet());
                        if (filters.size() == 0) filters.add(-1L);
                        contents = contentRepo.findAllByIdIsNotIn(filters, PageRequest.of(page - 1, renderItem));
                    } else { contents = contentRepo.findAllByCategory(category, PageRequest.of(page - 1, renderItem)); }
                } else {
                    contents = contentRepo.findAll(PageRequest.of(page - 1, renderItem));
                }
                lhm.put("contents", contents);
                lhm.put("totalElements", contents.getTotalElements());
            } catch (RuntimeException e) {
                lhm.put("contents", Collections.emptyList());
                lhm.put("totalElements", 0);
            } catch (Exception e) {
                e.printStackTrace();
                lhm.put("contents", Collections.emptyList());
                lhm.put("totalElements", 0);
            }
            return lhm;
        };
    }

    @GqlDataFetcher(type = GqlType.MUTATION)
    public DataFetcher<?> deleteContent() {
        return environment -> {
            long contentKey = Long.parseLong(environment.getArgument("id").toString());
            try {
                if (this.deleteContentDetails(contentKey))
                    throw new RuntimeException();
                else return new DefaultResponse(200);
            } catch (RuntimeException e) {
                return new DefaultResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
            }
        };
    }

    @GqlDataFetcher(type = GqlType.MUTATION)
    public DataFetcher<?> saveContentsToCategory() {
        return environment -> {
            long categoryKey = Long.parseLong(environment.getArgument("category").toString());
            List<Integer> keys = environment.getArgument("id");
            try {
                Category category = categoryRepo.findById(categoryKey).orElseThrow(IllegalArgumentException::new);
                List<Long> ctgContents = contentRepo.findAllByCategory(category).stream().map(Content::getId).collect(Collectors.toList()),
                        reqContents = contentRepo.findAllByIdIsIn(keys.stream().mapToLong(i -> (long) i).boxed().collect(Collectors.toSet()))
                                .stream().map(Content::getId).collect(Collectors.toList());

                Set<Long> nonDuplicatedIds = new HashSet<>(ctgContents);
                nonDuplicatedIds.addAll(reqContents);

                List<Content> contents = contentRepo.findAllByIdIsIn(nonDuplicatedIds);
                for (Content content : contents)
                    content.addCategory(category);

                contentRepo.saveAll(contents);
                return new DefaultResponse(200);
            } catch (IllegalArgumentException e) {
                return new DefaultResponse(HttpStatus.NOT_FOUND.value(), e.getMessage());
            } catch (RuntimeException e) {
                return new DefaultResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
            }
        };
    }

    @GqlDataFetcher(type = GqlType.MUTATION)
    public DataFetcher<?> deleteContentsInCategory() {
        return environment -> {
            long categoryKey = Long.parseLong(environment.getArgument("category").toString()),
                    contentKey = Long.parseLong(environment.getArgument("id").toString());
            try {
                Category category = categoryRepo.findById(categoryKey).orElseThrow(IllegalArgumentException::new);
                Content content = contentRepo.findById(contentKey).orElseThrow(IllegalArgumentException::new);

                content.filterCategory(category);
                contentRepo.save(content);
                return new DefaultResponse(200);
            } catch (IllegalArgumentException e) {
                return new DefaultResponse(HttpStatus.NOT_FOUND.value(), e.getMessage());
            } catch (RuntimeException e) {
                return new DefaultResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
            }
        };
    }

    public boolean deleteContentDetails(long contentKey) {
        try {
            Content content = contentRepo.findById(contentKey).orElseThrow(IndexOutOfBoundsException::new);
            contentRepo.updateSQLMode();

            wordRepo.disconnectWithParent(content);
            sentenceRepo.disconnectWithParent(content);
            // after delete child rows
            contentRepo.delete(content);
            return false;
        } catch (RuntimeException e) {
            e.printStackTrace();
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return true;
        }
    }
}
