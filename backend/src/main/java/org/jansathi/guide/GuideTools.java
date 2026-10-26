package org.jansathi.guide;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jansathi.account.Accounts.User;
import org.jansathi.account.Households;
import org.jansathi.assist.Retriever;
import org.jansathi.assist.Retriever.Passage;
import org.jansathi.content.BundleRepository;
import org.jansathi.content.ContentBundle;
import org.jansathi.content.Localized;
import org.jansathi.provider.llm.LlmProvider.Tool;
import org.jansathi.rules.MatchResponse;
import org.jansathi.rules.MatchResult;
import org.jansathi.rules.RuleTrace;
import org.jansathi.rules.RulesEngine;
import org.jansathi.rules.SchemeResult;
import org.jansathi.volunteer.CaseShares;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The guide's tools. Every fact and every result comes from here, never from the model: the rules
 * engine decides, the questionnaire logic is the app's, passages come from official sources. The
 * model only chooses what to call and explains what comes back.
 */
@Component
public class GuideTools {

	private static final JsonNodeFactory F = JsonNodeFactory.instance;

	private final BundleRepository bundles;
	private final RulesEngine engine;
	private final Retriever retriever;
	private final Locales locales;
	private final CaseShares shares;

	public GuideTools(BundleRepository bundles, RulesEngine engine, Retriever retriever, Locales locales, CaseShares shares) {
		this.bundles = bundles;
		this.engine = engine;
		this.retriever = retriever;
		this.locales = locales;
		this.shares = shares;
	}

	/** What happened during one turn, for the app to show next to the reply. */
	public static final class Events {
		public final List<Map<String, Object>> recorded = new ArrayList<>();
		public final Set<String> schemes = new LinkedHashSet<>();
		public final List<Passage> citations = new ArrayList<>();
		public ObjectNode question;
		public boolean changed;
		public String helpRequestId;
	}

	/**
	 * The state a turn works on.
	 *
	 * @param readOnly a helper's assistant: it may look, never change the case
	 */
	public record Session(ObjectNode household, String lang, User user, boolean readOnly, Events events) {}

	// ---------- definitions ----------

	private static ObjectNode schema(Map<String, String[]> props, String... required) {
		ObjectNode s = F.objectNode();
		s.put("type", "object");
		ObjectNode p = s.putObject("properties");
		props.forEach((name, typeAndDesc) -> {
			ObjectNode prop = p.putObject(name);
			if (!typeAndDesc[0].isEmpty()) {
				prop.put("type", typeAndDesc[0]);
			}
			prop.put("description", typeAndDesc[1]);
		});
		ArrayNode req = s.putArray("required");
		for (String r : required) {
			req.add(r);
		}
		return s;
	}

	public List<Tool> definitions(boolean readOnly) {
		List<Tool> tools = new ArrayList<>();
		tools.add(new Tool("next_question",
				"The next question to ask, chosen by the questionnaire logic, with its exact wording and answer options in the user's language. Ask it in your own short words and list the options.",
				schema(Map.of())));
		tools.add(new Tool("match_schemes",
				"Results of the rules engine for the current family member: which schemes may fit, which need more information (and what), which may not fit. The only source of eligibility results.",
				schema(Map.of())));
		tools.add(new Tool("scheme_details",
				"One scheme: what it gives, how to apply, its rules with this member's outcome, documents and official sources.",
				schema(Map.of("slug", new String[] {"string", "Scheme id from match_schemes or search_official_rules"}), "slug")));
		tools.add(new Tool("search_official_rules",
				"Search the official rules of all schemes for a question in the user's words. Returns passages with their source; empty means no official source was found.",
				schema(Map.of("query", new String[] {"string", "The question or key words"}), "query")));
		tools.add(new Tool("document_checklist",
				"Documents for the schemes on the user's checklist, whether they have each, and how to get the ones they don't have.",
				schema(Map.of())));
		if (!readOnly) {
			tools.add(new Tool("record_answer",
					"Save the user's answer to a question. Only use a value from the options next_question gave (or a number for an exact amount). Use null when the user does not know or does not want to say.",
					schema(Map.of(
							"field", new String[] {"string", "The field id from next_question"},
							"value", new String[] {"", "Option value (string), true/false for yes/no questions, a number for an exact amount, or null"}),
							"field", "value")));
			tools.add(new Tool("skip_question", "Skip a question the user does not want to answer now.",
					schema(Map.of("field", new String[] {"string", "The field id"}), "field")));
			tools.add(new Tool("add_to_checklist", "Add a scheme's documents to the user's document checklist.",
					schema(Map.of("slug", new String[] {"string", "Scheme id"}), "slug")));
			tools.add(new Tool("ask_human_helper",
					"Ask a human helper (a registered volunteer) to look at this case. Call only after the user said yes. The helper will see their answers and checklist for 3 days; the user can stop it any time.",
					schema(Map.of("include_conversation", new String[] {"boolean", "True only if the user agreed that the helper may read this conversation"}))));
		}
		return tools;
	}

	// ---------- execution ----------

	public JsonNode run(String name, JsonNode args, Session s) {
		return switch (name) {
			case "next_question" -> nextQuestion(s);
			case "match_schemes" -> matchSchemes(s);
			case "scheme_details" -> schemeDetails(s, args.path("slug").asString(""));
			case "search_official_rules" -> search(s, args.path("query").asString(""));
			case "document_checklist" -> checklist(s);
			case "record_answer" -> s.readOnly() ? error("not allowed") : recordAnswer(s, args.path("field").asString(""), args.get("value"));
			case "skip_question" -> s.readOnly() ? error("not allowed") : skip(s, args.path("field").asString(""));
			case "add_to_checklist" -> s.readOnly() ? error("not allowed") : addToChecklist(s, args.path("slug").asString(""));
			case "ask_human_helper" -> s.readOnly() ? error("not allowed") : askHelper(s, args.path("include_conversation").asBoolean(false));
			default -> error("unknown tool " + name);
		};
	}

	private static ObjectNode error(String message) {
		ObjectNode o = F.objectNode();
		o.put("error", message);
		return o;
	}

	private ContentBundle b() {
		return bundles.get();
	}

	private JsonNode fieldDef(String field) {
		return b().fieldsJson().path("fields").path(field);
	}

	private MatchResponse match(Session s) {
		ObjectNode m = Households.activeMember(s.household());
		Set<String> skipped = new HashSet<>();
		m.path("skipped").forEach(x -> skipped.add(x.asString()));
		JsonNode profile = m.path("profile");
		String state = profile.path("state").isString() ? profile.get("state").asString() : null;
		return engine.match(b().version(), b().schemesFor(state), profile, b().fields(), skipped, LocalDate.now());
	}

	private String name(String slug, String lang) {
		return b().scheme(slug).map(sc -> Localized.content(sc, lang).path("name").asString(slug)).orElse(slug);
	}

	/** The app's order: core questions first, then the engine's next_question (RULES_ENGINE §9.6). */
	public ObjectNode question(Session s) {
		ObjectNode m = Households.activeMember(s.household());
		JsonNode profile = m.path("profile");
		Set<String> skipped = new HashSet<>();
		m.path("skipped").forEach(x -> skipped.add(x.asString()));
		String field = null;
		for (JsonNode core : b().fieldsJson().path("core")) {
			if (!profile.has(core.asString()) && !skipped.contains(core.asString())) {
				field = core.asString();
				break;
			}
		}
		if (field == null) {
			field = match(s).nextQuestion();
		}
		if (field == null) {
			return null;
		}
		JsonNode def = fieldDef(field);
		boolean exact = profile.has(field) && "band".equals(def.path("type").asString());
		String lang = s.lang();
		ObjectNode q = F.objectNode();
		q.put("field", field);
		q.put("type", def.path("type").asString());
		q.put("exact", exact);
		q.put("question", locales.t(lang, exact ? "q.exact." + field : "q." + field));
		if (locales.has("help." + field)) {
			q.put("help", locales.t(lang, "help." + field));
		}
		ArrayNode opts = q.putArray("options");
		if (!exact) {
			if ("bool".equals(def.path("type").asString())) {
				opts.addObject().put("value", true).put("label", locales.t(lang, "opt.bool.yes"));
				opts.addObject().put("value", false).put("label", locales.t(lang, "opt.bool.no"));
				opts.addObject().putNull("value").put("label", locales.t(lang, "opt.bool.dont_know"));
			} else {
				for (JsonNode o : def.path("options")) {
					opts.addObject().put("value", o.path("value").asString()).put("label", locales.t(lang, "opt." + field + "." + o.path("value").asString()));
				}
			}
		}
		return q;
	}

	private JsonNode nextQuestion(Session s) {
		ObjectNode q = question(s);
		if (q == null) {
			ObjectNode done = F.objectNode();
			done.put("done", true);
			done.put("note", "No more questions: call match_schemes and explain the results.");
			return done;
		}
		s.events().question = q;
		return q;
	}

	/** Records an answer exactly as the app does (household fields for everyone, history for "back"). */
	private JsonNode recordAnswer(Session s, String field, JsonNode value) {
		JsonNode def = fieldDef(field);
		if (def.isMissingNode()) {
			return error("unknown field " + field);
		}
		String type = def.path("type").asString();
		JsonNode stored = null; // null = "don't know": removed from the profile and marked skipped
		String label;
		if (value == null || value.isNull()) {
			label = locales.t(s.lang(), "opt.bool.dont_know");
		} else if ("bool".equals(type)) {
			if (!value.isBoolean()) {
				return error("use true, false or null for " + field);
			}
			stored = value;
			label = locales.t(s.lang(), value.booleanValue() ? "opt.bool.yes" : "opt.bool.no");
		} else if (value.isNumber() && "band".equals(type) && def.path("allowExact").asBoolean(false)) {
			if (value.doubleValue() < 0 || value.doubleValue() > 1e9) {
				return error("that number is not plausible");
			}
			stored = value;
			label = value.asString();
		} else if (value.isString()) {
			JsonNode opt = null;
			for (JsonNode o : def.path("options")) {
				if (o.path("value").asString().equals(value.asString())) {
					opt = o;
				}
			}
			if (opt == null) {
				return error("not an option of " + field + "; call next_question for the options");
			}
			label = locales.t(s.lang(), "opt." + field + "." + value.asString());
			if (!opt.path("unknown").asBoolean(false)) {
				stored = "band".equals(type) ? opt.path("band").deepCopy() : value;
			}
		} else {
			return error("invalid value for " + field);
		}

		boolean household = "household".equals(def.path("scope").asString());
		ObjectNode active = Households.activeMember(s.household());
		ObjectNode entry = F.objectNode();
		entry.put("field", field);
		if (active.path("profile").has(field)) {
			entry.set("prev", active.path("profile").get(field).deepCopy());
		}
		entry.put("wasSkipped", contains(active.path("skipped"), field));
		for (JsonNode node : s.household().path("members")) {
			ObjectNode m = (ObjectNode) node;
			if (m != active && !household) {
				continue;
			}
			ObjectNode profile = (ObjectNode) m.path("profile");
			ArrayNode skipped = without((ArrayNode) m.path("skipped"), field);
			if (stored == null) {
				profile.remove(field);
				skipped.add(field);
			} else {
				profile.set(field, stored);
			}
			m.set("skipped", skipped);
		}
		((ArrayNode) active.path("history")).add(entry);
		s.events().changed = true;
		Map<String, Object> rec = new LinkedHashMap<>();
		rec.put("field", field);
		rec.put("label", label);
		s.events().recorded.add(rec);
		ObjectNode ok = F.objectNode();
		ok.put("saved", field);
		ok.put("answer", label);
		return ok;
	}

	private JsonNode skip(Session s, String field) {
		if (fieldDef(field).isMissingNode()) {
			return error("unknown field " + field);
		}
		boolean household = "household".equals(fieldDef(field).path("scope").asString());
		ObjectNode active = Households.activeMember(s.household());
		ObjectNode entry = F.objectNode();
		entry.put("field", field);
		entry.put("wasSkipped", false);
		if (active.path("profile").has(field)) {
			entry.set("prev", active.path("profile").get(field).deepCopy());
		}
		for (JsonNode node : s.household().path("members")) {
			ObjectNode m = (ObjectNode) node;
			if ((m == active || household) && !contains(m.path("skipped"), field)) {
				((ArrayNode) m.path("skipped")).add(field);
			}
		}
		((ArrayNode) active.path("history")).add(entry);
		s.events().changed = true;
		ObjectNode ok = F.objectNode();
		ok.put("skipped", field);
		return ok;
	}

	private static boolean contains(JsonNode array, String value) {
		for (JsonNode x : array) {
			if (x.asString("").equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static ArrayNode without(ArrayNode array, String value) {
		ArrayNode out = F.arrayNode();
		array.forEach(x -> {
			if (!x.asString("").equals(value)) {
				out.add(x);
			}
		});
		return out;
	}

	private JsonNode matchSchemes(Session s) {
		ObjectNode out = F.objectNode();
		ObjectNode m = Households.activeMember(s.household());
		if (m.path("profile").isEmpty() && m.path("skipped").isEmpty()) {
			out.put("note", "No answers yet: call next_question first.");
			return out;
		}
		ArrayNode likely = out.putArray("may_fit");
		ArrayNode need = out.putArray("need_more_information");
		ArrayNode not = out.putArray("may_not_fit");
		for (SchemeResult r : match(s).results()) {
			ObjectNode o = F.objectNode();
			o.put("slug", r.scheme());
			o.put("name", name(r.scheme(), s.lang()));
			o.put("trust_label", r.label().json());
			if (r.result() == MatchResult.NEED_MORE_INFO) {
				ArrayNode missing = o.putArray("still_to_answer");
				r.missingFields().forEach(f -> missing.add(locales.t(s.lang(), "q." + f)));
			}
			if (r.result() == MatchResult.LIKELY_ELIGIBLE) {
				likely.add(o);
				s.events().schemes.add(r.scheme());
			} else if (r.result() == MatchResult.NEED_MORE_INFO) {
				need.add(o);
				s.events().schemes.add(r.scheme());
			} else {
				not.add(o);
			}
		}
		out.put("all_drafts", b().preview());
		out.put("reminder", "These are results of written rules, not a decision. Say 'may fit', never that the person is eligible.");
		return out;
	}

	private JsonNode schemeDetails(Session s, String slug) {
		var scheme = b().scheme(slug).orElse(null);
		if (scheme == null) {
			return error("no scheme " + slug);
		}
		s.events().schemes.add(slug);
		String lang = s.lang();
		JsonNode content = Localized.content(scheme, lang);
		ObjectNode out = F.objectNode();
		out.put("slug", slug);
		out.put("name", content.path("name").asString());
		out.put("summary", content.path("summary").asString());
		out.put("benefit", content.path("benefit").asString());
		out.put("how_to_apply", content.path("how_to_apply").asString());
		out.put("official_website", scheme.path("official_apply_url").asString());
		out.put("status", scheme.path("status").asString());
		Map<String, JsonNode> sources = new LinkedHashMap<>();
		scheme.path("sources").forEach(src -> sources.put(src.path("id").asString(), src));
		SchemeResult r = match(s).results().stream().filter(x -> x.scheme().equals(slug)).findFirst().orElse(null);
		ArrayNode rules = out.putArray("rules");
		for (JsonNode rule : scheme.path("rules")) {
			if (!rule.path("hard").asBoolean(false)) {
				continue;
			}
			ObjectNode o = rules.addObject();
			o.put("rule", Localized.pick(rule.path("text"), lang).text());
			if (r != null) {
				r.rules().stream().filter(t -> t.code().equals(rule.path("code").asString())).findFirst()
						.map(RuleTrace::outcome).ifPresent(oc -> o.put("for_this_person", oc.json()));
			}
			JsonNode src = sources.get(rule.path("source").asString());
			if (src != null) {
				o.put("source", src.path("publisher").asString() + ", " + src.path("title").asString());
			}
			o.put("clause", rule.path("locator").asString(""));
		}
		ArrayNode docs = out.putArray("documents");
		scheme.path("documents").forEach(d -> docs.add(Localized.pick(b().document(d.path("code").asString()).path("name"), lang).text()
				+ (d.path("mandatory").asBoolean(false) ? "" : " (optional)")));
		ArrayNode srcs = out.putArray("sources");
		sources.values().forEach(src -> srcs.addObject().put("publisher", src.path("publisher").asString()).put("url", src.path("url").asString())
				.put("verified", !src.path("verified_at").isNull() && !src.path("verified_at").isMissingNode()));
		return out;
	}

	private JsonNode search(Session s, String query) {
		JsonNode profile = Households.activeMember(s.household()).path("profile");
		String state = profile.path("state").isString() ? profile.get("state").asString() : null;
		List<Passage> passages = retriever.retrieve(Retriever.redact(query), s.lang(), null, state);
		ObjectNode out = F.objectNode();
		ArrayNode list = out.putArray("passages");
		for (Passage p : passages) {
			int n = s.events().citations.size() + 1;
			s.events().citations.add(p);
			s.events().schemes.add(p.scheme());
			list.addObject().put("id", "s" + n).put("scheme", p.schemeName().text()).put("slug", p.scheme())
					.put("rule", p.text().text()).put("source", p.source().path("publisher").asString()).put("clause", p.locator());
		}
		if (passages.isEmpty()) {
			out.put("note", "No official source matched. Say: I couldn't find an official source for this.");
		}
		return out;
	}

	private JsonNode addToChecklist(Session s, String slug) {
		if (b().scheme(slug).isEmpty()) {
			return error("no scheme " + slug);
		}
		ArrayNode schemes = (ArrayNode) s.household().path("checklist").path("schemes");
		if (!contains(schemes, slug)) {
			schemes.add(slug);
			s.events().changed = true;
		}
		ObjectNode ok = F.objectNode();
		ok.put("added", slug);
		return ok;
	}

	private JsonNode checklist(Session s) {
		String lang = s.lang();
		JsonNode have = s.household().path("checklist").path("have");
		Map<String, ObjectNode> docs = new LinkedHashMap<>();
		for (JsonNode slug : s.household().path("checklist").path("schemes")) {
			b().scheme(slug.asString()).ifPresent(scheme -> scheme.path("documents").forEach(d -> {
				String code = d.path("code").asString();
				ObjectNode o = docs.computeIfAbsent(code, c -> {
					JsonNode doc = b().document(c);
					ObjectNode n = F.objectNode();
					n.put("document", Localized.pick(doc.path("name"), lang).text());
					n.put("required", false);
					n.put("status", have.has(c) ? (have.get(c).asBoolean() ? "has it" : "does not have it") : "not marked");
					n.put("how_to_get_it", Localized.pick(doc.path("how_to"), lang).text());
					return n;
				});
				if (d.path("mandatory").asBoolean(false)) {
					o.put("required", true);
				}
			}));
		}
		ObjectNode out = F.objectNode();
		out.set("documents", F.arrayNode().addAll(docs.values()));
		if (docs.isEmpty()) {
			out.put("note", "The checklist is empty: offer to add schemes that may fit.");
		}
		return out;
	}

	private JsonNode askHelper(Session s, boolean includeConversation) {
		List<String> scopes = new ArrayList<>(List.of("profile", "checklist"));
		if (includeConversation) {
			scopes.add("conversation");
		}
		var share = shares.create(s.user(), scopes, Duration.ofDays(3), true, s.lang());
		s.events().helpRequestId = share.id();
		ObjectNode ok = F.objectNode();
		ok.put("requested", true);
		ok.put("note", "A helper will look at the case. The user can see who looked and stop it on their account page.");
		return ok;
	}
}
